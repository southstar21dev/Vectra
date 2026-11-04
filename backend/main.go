package main

import (
	"errors"
	"log"

	"github.com/pocketbase/pocketbase"
	"github.com/pocketbase/pocketbase/core"
	"github.com/pocketbase/pocketbase/plugins/migratecmd"
	"github.com/spf13/cobra"

	"vectra/pb/internal/migrate"
	"vectra/pb/internal/passkeys"
	"vectra/pb/internal/push"
	"vectra/pb/internal/routes"
	"vectra/pb/internal/schema"
	_ "vectra/pb/migrations"
)

func main() {
	app := pocketbase.New()

	schema.RegisterTimestamps(app)
	// Reject push subscriptions whose endpoint is not a known push service, so
	// the send path can never be aimed at an arbitrary host.
	push.RegisterSubscriptionValidation(app)

	// Reclaim expired passkey challenges (the public login/start writes one per
	// call and authentication challenges have no user, so cascade delete never
	// touches them).
	passkeys.RegisterChallengeCleanup(app)

	migratecmd.MustRegister(app, app.RootCmd, migratecmd.Config{Automigrate: false})

	registerMigrateSupabaseCommand(app)

	app.OnServe().BindFunc(func(se *core.ServeEvent) error {
		routes.Register(se)
		return se.Next()
	})

	if err := app.Start(); err != nil {
		log.Fatal(err)
	}
}

// registerMigrateSupabaseCommand adds the one-shot `migrate-supabase` command
// that imports users, passkeys and per-user data from a Supabase Postgres
// database into this PocketBase instance. With --verify it instead runs the
// read-only verification checks against the database already on disk (no
// import) and exits non-zero if any fail. A re-run is insert-only (existing
// rows are skipped) unless --refresh is given.
func registerMigrateSupabaseCommand(app *pocketbase.PocketBase) {
	cmd := &cobra.Command{
		Use:   "migrate-supabase",
		Short: "Import users and data from Supabase Postgres",
		RunE: func(cmd *cobra.Command, _ []string) error {
			dsn, _ := cmd.Flags().GetString("dsn")
			dry, _ := cmd.Flags().GetBool("dry-run")
			verify, _ := cmd.Flags().GetBool("verify")
			refresh, _ := cmd.Flags().GetBool("refresh")
			testEmail, _ := cmd.Flags().GetString("test-email")
			testPassword, _ := cmd.Flags().GetString("test-password")

			if verify && dry {
				return errors.New("--verify cannot be combined with --dry-run (nothing was imported)")
			}
			if verify && refresh {
				return errors.New("--verify cannot be combined with --refresh (verify is read-only)")
			}

			// --verify is read-only: it checks the database already on the
			// volume and never re-runs the importer, so an operator can verify
			// a completed import without mutating the instance.
			if verify {
				vrep, err := migrate.VerifyWithOptions(app, dsn, migrate.VerifyOptions{
					TestEmail:    testEmail,
					TestPassword: testPassword,
				})
				if err != nil {
					return err
				}
				cmd.Println(vrep.String())
				return vrep.Err()
			}

			report, err := migrate.Run(app, dsn, migrate.Options{DryRun: dry, Refresh: refresh})
			if err != nil {
				return err
			}
			cmd.Println(report.String())
			return nil
		},
	}
	cmd.Flags().String("dsn", "", "Postgres DSN of the Supabase database (required)")
	cmd.Flags().Bool("dry-run", false, "connect and report counts without writing anything")
	cmd.Flags().Bool("refresh", false, "overwrite already-imported users, passkeys and keyed data rows from the source (default: insert-only, existing rows are skipped)")
	cmd.Flags().Bool("verify", false, "verify counts, ciphertext, integrity and login against the existing database (read-only; no import)")
	cmd.Flags().String("test-email", "", "test account email for a live-login assertion during --verify")
	cmd.Flags().String("test-password", "", "test account password for a live-login assertion during --verify")
	app.RootCmd.AddCommand(cmd)
}
