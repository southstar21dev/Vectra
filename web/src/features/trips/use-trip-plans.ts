// src/features/trips/use-trip-plans.ts

import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { queryKeys } from '@/lib/query-keys';
import { pb } from '@/lib/pocketbase';
import { requireSession } from '@/lib/auth-helpers';
import { withUserId, type Json } from '@/lib/pocketbase-mappers';
import { encryptField, decryptField } from '@/lib/encryption';
import { applyDirectionChange, normalizeLeg, normalizeTripDirection } from './types';
import type { TripPlan, NewTripInput, UpdateTripInput } from './types';
import type { TripPlansRecord } from '@/types/pocketbase-types';

/** Raw PocketBase `trip_plans` row (fields optional in the generated types). */
type TripPlanRow = TripPlansRecord<Json>;

async function encryptTripInput(input: NewTripInput): Promise<{ name: string; legs: string }> {
  return {
    name: (await encryptField(input.name)) as string,
    legs: (await encryptField(JSON.stringify(input.legs))) as string,
  };
}

async function decryptTripPlan(row: TripPlanRow): Promise<TripPlan> {
  // `legs` is a JSON field but the app stores an encrypted string inside it.
  const rawLegs = typeof row.legs === 'string' ? row.legs : JSON.stringify(row.legs);
  const raw = JSON.parse((await decryptField(rawLegs)) as string);
  // PocketBase stores the owner as the `user` relation; withUserId mirrors it
  // onto `user_id` so the domain type keeps reading it as before.
  const { user_id } = withUserId(row);
  return {
    id: row.id,
    userId: user_id,
    name: (await decryptField(row.name ?? '')) as string,
    legs: Array.isArray(raw)
      ? raw
          .filter((leg): leg is Record<string, unknown> => !!leg && typeof leg === 'object')
          .map(normalizeLeg)
      : [],
    sortOrder: row.sort_order ?? 0,
    createdAt: row.created_at ?? '',
    direction: normalizeTripDirection(row.direction),
  };
}

export function useTripPlans() {
  return useQuery({
    queryKey: queryKeys.tripPlans,
    queryFn: async () => {
      // Owner scoping is enforced server-side by the collection's list rule.
      const rows = await pb.collection<TripPlanRow>('trip_plans').getFullList({
        sort: 'sort_order',
      });
      const plans = await Promise.all(
        rows.map(async (row) => {
          try {
            return await decryptTripPlan(row);
          } catch (err) {
            // One undecryptable/corrupt row must not hide every other trip.
            console.warn('[trip-plans] Skipping undecryptable plan', row.id, err);
            return null;
          }
        })
      );
      return plans.filter((plan): plan is TripPlan => plan !== null);
    },
    staleTime: 1000 * 60 * 5,
    retry: false,
  });
}

/**
 * Clears `direction` from `conflicting` rows, then runs `write`. If the write
 * fails, the cleared designations are put back so a failed save never leaves
 * the other trip without its Home/Uni role on the server.
 */
async function handOffDirection<T>(
  conflicting: TripPlanRow[],
  direction: string,
  write: () => Promise<T>
): Promise<T> {
  await Promise.all(
    conflicting.map((row) => pb.collection('trip_plans').update(row.id, { direction: null }))
  );
  try {
    return await write();
  } catch (err) {
    await Promise.allSettled(
      conflicting.map((row) => pb.collection('trip_plans').update(row.id, { direction }))
    );
    throw err;
  }
}

export function useAddTripPlan() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: NewTripInput) => {
      const encrypted = await encryptTripInput(input);
      const session = requireSession();
      const userId = session.record!.id;

      // One trip per direction: moving the designation must clear it elsewhere
      // before this row takes it (the partial unique index enforces the same).
      const existing = await pb.collection<TripPlanRow>('trip_plans').getFullList({
        sort: 'sort_order',
      });

      const maxSort = existing.length > 0
        ? Math.max(...existing.map((row) => row.sort_order ?? -1))
        : -1;

      const create = () =>
        pb.collection<TripPlanRow>('trip_plans').create({
          user: userId,
          name: encrypted.name,
          legs: encrypted.legs,
          sort_order: maxSort + 1,
          direction: input.direction ?? null,
        });

      const record = input.direction != null
        ? await handOffDirection(
            existing.filter((row) => row.direction === input.direction),
            input.direction,
            create
          )
        : await create();
      return decryptTripPlan(record);
    },
    onMutate: async (input) => {
      await queryClient.cancelQueries({ queryKey: queryKeys.tripPlans });
      const previous = queryClient.getQueryData<TripPlan[]>(queryKeys.tripPlans);
      // Optimistic: add placeholder
      const optimistic: TripPlan = {
        id: crypto.randomUUID(),
        userId: '',
        name: input.name,
        legs: input.legs,
        sortOrder: (previous?.length ?? 0),
        createdAt: new Date().toISOString(),
        direction: input.direction ?? null,
      };
      queryClient.setQueryData<TripPlan[]>(queryKeys.tripPlans, (old) =>
        applyDirectionChange([...(old ?? []), optimistic], optimistic.id, input.direction ?? null)
      );
      return { previous };
    },
    onError: (_err, _input, context) => {
      if (context?.previous) {
        queryClient.setQueryData(queryKeys.tripPlans, context.previous);
      }
    },
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.tripPlans });
    },
  });
}

export function useUpdateTripPlan() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: UpdateTripInput) => {
      const updates: {
        name?: string;
        legs?: string;
        sort_order?: number;
        direction?: string | null;
      } = {};
      if (input.name !== undefined) {
        updates.name = (await encryptField(input.name)) as string;
      }
      if (input.legs !== undefined) {
        updates.legs = (await encryptField(JSON.stringify(input.legs))) as string;
      }
      if (input.sortOrder !== undefined) {
        updates.sort_order = input.sortOrder;
      }
      const update = () => pb.collection('trip_plans').update(input.id, updates);
      if (input.direction !== undefined) {
        updates.direction = input.direction;
        // One trip per direction: clear the designation on other rows first.
        // Owner scoping is enforced server-side by the collection's list rule.
        if (input.direction != null) {
          const conflicting = await pb.collection<TripPlanRow>('trip_plans').getFullList({
            filter: pb.filter('direction = {:d} && id != {:id}', {
              d: input.direction,
              id: input.id,
            }),
          });
          await handOffDirection(conflicting, input.direction, update);
          return;
        }
      }

      await update();
    },
    onMutate: async (input) => {
      await queryClient.cancelQueries({ queryKey: queryKeys.tripPlans });
      const previous = queryClient.getQueryData<TripPlan[]>(queryKeys.tripPlans);
      queryClient.setQueryData<TripPlan[]>(queryKeys.tripPlans, (old) => {
        const merged = (old ?? []).map((t) =>
          t.id === input.id
            ? { ...t, ...(input.name !== undefined && { name: input.name }), ...(input.legs !== undefined && { legs: input.legs }) }
            : t
        );
        if (input.direction === undefined) return merged;
        return applyDirectionChange(merged, input.id, input.direction);
      });
      return { previous };
    },
    onError: (_err, _input, context) => {
      if (context?.previous) {
        queryClient.setQueryData(queryKeys.tripPlans, context.previous);
      }
    },
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.tripPlans });
    },
  });
}

export function useDeleteTripPlan() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      // Owner scoping is enforced server-side by the collection's delete rule.
      await pb.collection('trip_plans').delete(id);
    },
    onMutate: async (id) => {
      await queryClient.cancelQueries({ queryKey: queryKeys.tripPlans });
      const previous = queryClient.getQueryData<TripPlan[]>(queryKeys.tripPlans);
      queryClient.setQueryData<TripPlan[]>(queryKeys.tripPlans, (old) =>
        (old ?? []).filter((t) => t.id !== id)
      );
      return { previous };
    },
    onError: (_err, _input, context) => {
      if (context?.previous) {
        queryClient.setQueryData(queryKeys.tripPlans, context.previous);
      }
    },
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.tripPlans });
    },
  });
}
