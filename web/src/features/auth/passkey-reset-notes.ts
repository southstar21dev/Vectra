/**
 * The server deletes every passkey when the password is reset or changed, or the email changes
 * (the account's token key rotates), so each of those forms says so before the user commits.
 */
export const PASSKEY_RESET_NOTES = {
  signup: "If you later reset or change your password or email, you'll need to set biometric sign-in up again.",
  password: 'Changing your password turns off biometric sign-in on all your devices.',
  email: 'Changing your email turns off biometric sign-in on all your devices.',
} as const;
