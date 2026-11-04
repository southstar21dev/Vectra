/**
* This file was @generated using pocketbase-typegen
*/

import type PocketBase from 'pocketbase'
import type { RecordService } from 'pocketbase'

export const Collections = {
	Authorigins: "_authOrigins",
	Externalauths: "_externalAuths",
	Mfas: "_mfas",
	Otps: "_otps",
	Superusers: "_superusers",
	CourseTrackerData: "course_tracker_data",
	NeptunScheduleEvents: "neptun_schedule_events",
	PasskeyChallenges: "passkey_challenges",
	Passkeys: "passkeys",
	PushSubscriptions: "push_subscriptions",
	ScheduleEvents: "schedule_events",
	ScheduleItems: "schedule_items",
	TransitTrackers: "transit_trackers",
	TripPlans: "trip_plans",
	Users: "users",
} as const
export type Collections = typeof Collections[keyof typeof Collections]

// Alias types for improved usability
export type IsoDateString = string
export type IsoAutoDateString = string & { readonly autodate: unique symbol }
export type RecordIdString = string
export type FileNameString = string & { readonly filename: unique symbol }
export type HTMLString = string

type ExpandType<T> = unknown extends T
	? T extends unknown
		? { expand?: unknown }
		: { expand: T }
	: { expand: T }

// System fields
export type BaseSystemFields<T = unknown> = {
	id: RecordIdString
	collectionId: string
	collectionName: Collections
} & ExpandType<T>

export type AuthSystemFields<T = unknown> = {
	email: string
	emailVisibility: boolean
	username: string
	verified: boolean
} & BaseSystemFields<T>

// Record types for each collection

export type AuthoriginsRecord = {
	collectionRef: string
	created: IsoAutoDateString
	fingerprint: string
	id: string
	recordRef: string
	updated: IsoAutoDateString
}

export type ExternalauthsRecord = {
	collectionRef: string
	created: IsoAutoDateString
	id: string
	provider: string
	providerId: string
	recordRef: string
	updated: IsoAutoDateString
}

export type MfasRecord = {
	collectionRef: string
	created: IsoAutoDateString
	id: string
	method: string
	recordRef: string
	updated: IsoAutoDateString
}

export type OtpsRecord = {
	collectionRef: string
	created: IsoAutoDateString
	id: string
	password: string
	recordRef: string
	sentTo?: string
	updated: IsoAutoDateString
}

export type SuperusersRecord = {
	created: IsoAutoDateString
	email: string
	emailVisibility?: boolean
	id: string
	password: string
	tokenKey: string
	updated: IsoAutoDateString
	verified?: boolean
}

export type CourseTrackerDataRecord = {
	created_at?: IsoDateString
	data: string
	id: string
	updated_at?: IsoDateString
	user: RecordIdString
}

export type NeptunScheduleEventsRecord = {
	color?: string
	course_code?: string
	course_name?: string
	created_at?: IsoDateString
	day_of_week?: string
	end_time?: string
	event_type?: string
	id: string
	instructor?: string
	location?: string
	semester?: string
	semester_end?: string
	semester_start?: string
	start_time?: string
	updated_at?: IsoDateString
	user: RecordIdString
}

export type PasskeyChallengesRecord<Tsession_data = unknown> = {
	challenge_type: string
	expires_at: IsoDateString
	id: string
	session_data: null | Tsession_data
	user?: RecordIdString
}

export type PasskeysRecord<Ttransports = unknown> = {
	aaguid?: string
	attestation_type?: string
	backed_up?: boolean
	backup_eligible?: boolean
	created_at?: IsoDateString
	credential_id: string
	friendly_name?: string
	id: string
	last_used_at?: IsoDateString
	public_key: string
	sign_count?: number
	transports?: null | Ttransports
	user: RecordIdString
}

export type PushSubscriptionsRecord<Tsubscription = unknown> = {
	created_at?: IsoDateString
	device_name?: string
	id: string
	subscription: null | Tsubscription
	transport?: string
	updated_at?: IsoDateString
	user: RecordIdString
}

export const ScheduleEventsAttendanceTypeOptions = {
	"lecture": "lecture",
	"seminar": "seminar",
	"lab": "lab",
	"unknown": "unknown",
} as const
export type ScheduleEventsAttendanceTypeOptions = typeof ScheduleEventsAttendanceTypeOptions[keyof typeof ScheduleEventsAttendanceTypeOptions]
export type ScheduleEventsRecord<Tcsv_metadata = unknown> = {
	attendance_type?: ScheduleEventsAttendanceTypeOptions
	color?: string
	course_code?: string
	course_name?: string
	created_at?: IsoDateString
	csv_metadata?: null | Tcsv_metadata
	day_of_week?: number
	end_time?: string
	event_type?: string
	id: string
	instructor?: string
	location?: string
	semester?: string
	semester_end?: IsoDateString
	semester_start?: IsoDateString
	start_time?: string
	updated_at?: IsoDateString
	user: RecordIdString
}

export type ScheduleItemsRecord = {
	client_id: string
	created_at?: IsoDateString
	id: string
	payload: string
	updated_at?: IsoDateString
	user: RecordIdString
}

export type TransitTrackersRecord<Tstops = unknown> = {
	created_at?: IsoDateString
	dedupe_key: string
	headsign?: string
	id: string
	is_favorite?: boolean
	route_color?: string
	route_id?: string
	route_long_name?: string
	route_short_name?: string
	route_text_color?: string
	route_type?: string
	sort_order?: number
	stop_id?: string
	stop_name?: string
	stops?: null | Tstops
	updated_at?: IsoDateString
	user: RecordIdString
}

export type TripPlansRecord<Tlegs = unknown> = {
	created_at?: IsoDateString
	direction?: string
	id: string
	legs?: null | Tlegs
	name?: string
	sort_order?: number
	updated_at?: IsoDateString
	user: RecordIdString
}

export type UsersRecord<Tappearance = unknown, Tpreferences = unknown> = {
	appearance?: null | Tappearance
	avatar?: FileNameString
	avatar_url?: string
	created: IsoAutoDateString
	created_at?: IsoDateString
	email: string
	emailVisibility?: boolean
	full_name?: string
	id: string
	legacy_id?: string
	name?: string
	neptun_ics_url?: string
	neptun_last_sync?: IsoDateString
	password: string
	preferences?: null | Tpreferences
	tokenKey: string
	updated: IsoAutoDateString
	updated_at?: IsoDateString
	verified?: boolean
}

// Response types include system fields and match responses from the PocketBase API
export type AuthoriginsResponse<Texpand = unknown> = Required<AuthoriginsRecord> & BaseSystemFields<Texpand>
export type ExternalauthsResponse<Texpand = unknown> = Required<ExternalauthsRecord> & BaseSystemFields<Texpand>
export type MfasResponse<Texpand = unknown> = Required<MfasRecord> & BaseSystemFields<Texpand>
export type OtpsResponse<Texpand = unknown> = Required<OtpsRecord> & BaseSystemFields<Texpand>
export type SuperusersResponse<Texpand = unknown> = Required<SuperusersRecord> & AuthSystemFields<Texpand>
export type CourseTrackerDataResponse<Texpand = unknown> = Required<CourseTrackerDataRecord> & BaseSystemFields<Texpand>
export type NeptunScheduleEventsResponse<Texpand = unknown> = Required<NeptunScheduleEventsRecord> & BaseSystemFields<Texpand>
export type PasskeyChallengesResponse<Tsession_data = unknown, Texpand = unknown> = Required<PasskeyChallengesRecord<Tsession_data>> & BaseSystemFields<Texpand>
export type PasskeysResponse<Ttransports = unknown, Texpand = unknown> = Required<PasskeysRecord<Ttransports>> & BaseSystemFields<Texpand>
export type PushSubscriptionsResponse<Tsubscription = unknown, Texpand = unknown> = Required<PushSubscriptionsRecord<Tsubscription>> & BaseSystemFields<Texpand>
export type ScheduleEventsResponse<Tcsv_metadata = unknown, Texpand = unknown> = Required<ScheduleEventsRecord<Tcsv_metadata>> & BaseSystemFields<Texpand>
export type ScheduleItemsResponse<Texpand = unknown> = Required<ScheduleItemsRecord> & BaseSystemFields<Texpand>
export type TransitTrackersResponse<Tstops = unknown, Texpand = unknown> = Required<TransitTrackersRecord<Tstops>> & BaseSystemFields<Texpand>
export type TripPlansResponse<Tlegs = unknown, Texpand = unknown> = Required<TripPlansRecord<Tlegs>> & BaseSystemFields<Texpand>
export type UsersResponse<Tappearance = unknown, Tpreferences = unknown, Texpand = unknown> = Required<UsersRecord<Tappearance, Tpreferences>> & AuthSystemFields<Texpand>

// Types containing all Records and Responses, useful for creating typing helper functions

export type CollectionRecords = {
	_authOrigins: AuthoriginsRecord
	_externalAuths: ExternalauthsRecord
	_mfas: MfasRecord
	_otps: OtpsRecord
	_superusers: SuperusersRecord
	course_tracker_data: CourseTrackerDataRecord
	neptun_schedule_events: NeptunScheduleEventsRecord
	passkey_challenges: PasskeyChallengesRecord
	passkeys: PasskeysRecord
	push_subscriptions: PushSubscriptionsRecord
	schedule_events: ScheduleEventsRecord
	schedule_items: ScheduleItemsRecord
	transit_trackers: TransitTrackersRecord
	trip_plans: TripPlansRecord
	users: UsersRecord
}

export type CollectionResponses = {
	_authOrigins: AuthoriginsResponse
	_externalAuths: ExternalauthsResponse
	_mfas: MfasResponse
	_otps: OtpsResponse
	_superusers: SuperusersResponse
	course_tracker_data: CourseTrackerDataResponse
	neptun_schedule_events: NeptunScheduleEventsResponse
	passkey_challenges: PasskeyChallengesResponse
	passkeys: PasskeysResponse
	push_subscriptions: PushSubscriptionsResponse
	schedule_events: ScheduleEventsResponse
	schedule_items: ScheduleItemsResponse
	transit_trackers: TransitTrackersResponse
	trip_plans: TripPlansResponse
	users: UsersResponse
}

// Utility types for create/update operations

type ProcessCreateAndUpdateFields<T> = Omit<{
	// Omit AutoDate fields
	[K in keyof T as Extract<T[K], IsoAutoDateString> extends never ? K : never]: 
		// Convert FileNameString to File
		T[K] extends infer U ? 
			U extends (FileNameString | FileNameString[]) ? 
				U extends any[] ? File[] : File 
			: U
		: never
}, 'id'>

// Create type for Auth collections
export type CreateAuth<T> = {
	id?: RecordIdString
	email: string
	emailVisibility?: boolean
	password: string
	passwordConfirm: string
	verified?: boolean
} & ProcessCreateAndUpdateFields<T>

// Create type for Base collections
export type CreateBase<T> = {
	id?: RecordIdString
} & ProcessCreateAndUpdateFields<T>

// Update type for Auth collections
export type UpdateAuth<T> = Partial<
	Omit<ProcessCreateAndUpdateFields<T>, keyof AuthSystemFields>
> & {
	email?: string
	emailVisibility?: boolean
	oldPassword?: string
	password?: string
	passwordConfirm?: string
	verified?: boolean
}

// Update type for Base collections
export type UpdateBase<T> = Partial<
	Omit<ProcessCreateAndUpdateFields<T>, keyof BaseSystemFields>
>

// Get the correct create type for any collection
export type Create<T extends keyof CollectionResponses> =
	CollectionResponses[T] extends AuthSystemFields
		? CreateAuth<CollectionRecords[T]>
		: CreateBase<CollectionRecords[T]>

// Get the correct update type for any collection
export type Update<T extends keyof CollectionResponses> =
	CollectionResponses[T] extends AuthSystemFields
		? UpdateAuth<CollectionRecords[T]>
		: UpdateBase<CollectionRecords[T]>

// Type for usage with type asserted PocketBase instance
// https://github.com/pocketbase/js-sdk#specify-typescript-definitions

export type TypedPocketBase = {
	collection<T extends keyof CollectionResponses>(
		idOrName: T
	): RecordService<CollectionResponses[T]>
} & PocketBase
