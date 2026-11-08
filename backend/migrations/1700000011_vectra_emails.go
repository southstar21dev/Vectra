package migrations

import (
	"github.com/pocketbase/pocketbase/core"
	m "github.com/pocketbase/pocketbase/migrations"
)

// Brands the emails PocketBase sends to users. Without this they come from "Acme" (PocketBase's
// placeholder app name) and the reset link opens the superuser dashboard's page.
//
//   - App name "Acme" becomes "Vectra"; a name an operator already chose is left alone.
//   - The users' password-reset email links to resetpage.Path, the Vectra-styled page.
//   - The new-location login alert (on by default) is turned off: Vectra doesn't want it, and every
//     sign-in from a new device would otherwise cost an email.
//
// PocketBase wraps each body in its own minimal layout (white page, 20px padding), so these are
// table-based with inline styles only: what every mail client renders the same way.
// The sender address and SMTP server are set in the dashboard, not here.
func init() {
	m.Register(func(app core.App) error {
		settings := app.Settings()
		if settings.Meta.AppName == "" || settings.Meta.AppName == "Acme" {
			settings.Meta.AppName = "Vectra"
			if err := app.Save(settings); err != nil {
				return err
			}
		}

		users, err := app.FindCollectionByNameOrId("users")
		if err != nil {
			return err
		}
		users.ResetPasswordTemplate = core.EmailTemplate{Subject: resetSubject, Body: resetBody}
		users.AuthAlert.Enabled = false
		return app.Save(users)
	}, func(app core.App) error {
		users, err := app.FindCollectionByNameOrId("users")
		if err != nil {
			return err
		}
		// A new auth collection carries PocketBase's default templates.
		defaults := core.NewAuthCollection("defaults")
		users.ResetPasswordTemplate = defaults.ResetPasswordTemplate
		users.AuthAlert.Enabled = defaults.AuthAlert.Enabled
		return app.Save(users)
	})
}

const resetSubject = "Reset your Vectra password"

// emailOpen and emailClose frame the message: the icon, then a rounded card.
const emailOpen = `<table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="background:#F3F1F8;border-radius:24px;">
<tr><td align="center" style="padding:32px 16px;">
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="max-width:480px;">
<tr><td align="center" style="padding:0 0 20px;">
<img src="{APP_URL}/reset-password/icon.png" width="64" height="64" alt="Vectra" style="display:block;width:64px;height:64px;border-radius:16px;border:0;" />
</td></tr>
<tr><td style="background:#FFFFFF;border:1px solid #E7E2F3;border-radius:20px;padding:32px 28px;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;color:#17151F;">`

const emailClose = `</td></tr>
<tr><td align="center" style="padding:20px 8px 0;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;font-size:12px;line-height:18px;color:#8A8698;">
Vectra · your timetable and Budapest transit<br />
Questions? <a href="mailto:support@southstar21.dev" style="color:#7C3AED;text-decoration:none;">support@southstar21.dev</a>
</td></tr>
</table>
</td></tr>
</table>`

const resetBody = emailOpen + `
<h1 style="margin:0 0 12px;font-size:22px;line-height:28px;font-weight:700;color:#17151F;">Reset your password</h1>
<p style="margin:0 0 24px;font-size:15px;line-height:22px;color:#4A4658;">Someone asked to reset the password for your Vectra account. If that was you, choose a new one below.</p>
<table role="presentation" cellpadding="0" cellspacing="0" border="0" style="margin:0 0 24px;"><tr>
<td align="center" bgcolor="#8B5CF6" style="border-radius:999px;background:#8B5CF6;">
<a href="{APP_URL}/reset-password#{TOKEN}" target="_blank" rel="noopener" style="display:inline-block;padding:14px 28px;font-size:15px;line-height:20px;font-weight:700;color:#FFFFFF;text-decoration:none;border-radius:999px;">Choose a new password</a>
</td></tr></table>
<p style="margin:0 0 20px;font-size:13px;line-height:20px;color:#6B6779;">The link works once, for 30 minutes. If you didn't ask for this, ignore this email: your password stays the same.</p>
<p style="margin:0;padding-top:16px;border-top:1px solid #EEEAF6;font-size:12px;line-height:18px;color:#8A8698;">Button not working? Paste this into your browser:<br />
<a href="{APP_URL}/reset-password#{TOKEN}" style="color:#7C3AED;word-break:break-all;">{APP_URL}/reset-password#{TOKEN}</a></p>
` + emailClose
