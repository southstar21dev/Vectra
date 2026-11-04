import { describe, it, expect } from 'vitest';
import {
  loginErrorMessage,
  pocketbaseErrorField,
  isValidEmail,
  validatePassword,
  validateConfirmPassword,
  DELETE_ACCOUNT_CONFIRM_TEXT,
  deleteConfirmationMatches,
  functionsErrorMessage,
  pocketbaseFieldErrorMessage,
} from './account-settings-logic';

describe('account settings validation', () => {
  it('accepts well-formed email addresses', () => {
    expect(isValidEmail('student@uni.hu')).toBe(true);
    expect(isValidEmail('a.b+tag@example.co.uk')).toBe(true);
  });

  it('rejects malformed email addresses', () => {
    expect(isValidEmail('')).toBe(false);
    expect(isValidEmail('not-an-email')).toBe(false);
    expect(isValidEmail('missing@domain')).toBe(false);
    expect(isValidEmail(' spaced@out.com ')).toBe(false);
  });

  it('requires a non-empty password', () => {
    expect(validatePassword('')).toBe('Password is required');
  });

  it('requires at least 8 characters (the PocketBase minimum)', () => {
    expect(validatePassword('1234567')).toBe('Password must be at least 8 characters');
    expect(validatePassword('12345678')).toBeNull();
  });

  it('requires the confirmation to match the new password', () => {
    expect(validateConfirmPassword('newpass1', '')).toBe('Please confirm your new password');
    expect(validateConfirmPassword('newpass1', 'newpass2')).toBe('Passwords do not match');
    expect(validateConfirmPassword('newpass1', 'newpass1')).toBeNull();
  });

  it('requires the literal delete-confirmation text', () => {
    expect(DELETE_ACCOUNT_CONFIRM_TEXT).toBe('DELETE');
    expect(deleteConfirmationMatches('DELETE')).toBe(true);
    expect(deleteConfirmationMatches(' delete ')).toBe(true);
    expect(deleteConfirmationMatches('Delete My Account')).toBe(false);
  });

  it('extracts the server error from a functions.invoke failure', async () => {
    const response = new Response(JSON.stringify({ error: 'Account not found' }), { status: 404 });
    expect(await functionsErrorMessage({ message: 'Edge Function returned a non-2xx status code', context: response })).toBe('Account not found');
    expect(await functionsErrorMessage({ message: 'Network error' })).toBe('Network error');
    expect(await functionsErrorMessage(null)).toBeNull();
  });
});

describe('pocketbaseFieldErrorMessage', () => {
  it('surfaces the first field error with its label', () => {
    const err = {
      message: 'Failed to create record.',
      response: {
        code: 400,
        message: 'Failed to create record.',
        data: { password: { code: 'validation_min_text_constraint', message: 'Must be at least 8 character(s).' } },
      },
    };
    expect(pocketbaseFieldErrorMessage(err)).toBe('Password: Must be at least 8 character(s).');
  });

  it('returns null without field errors', () => {
    expect(pocketbaseFieldErrorMessage(new Error('boom'))).toBeNull();
    expect(pocketbaseFieldErrorMessage({ response: { data: {} } })).toBeNull();
    expect(pocketbaseFieldErrorMessage(null)).toBeNull();
  });
});

describe('loginErrorMessage', () => {
  it("maps PocketBase's generic auth failure to plain copy", () => {
    expect(loginErrorMessage(new Error('Failed to authenticate.'))).toBe('Incorrect email or password.');
  });

  it('keeps any other message and falls back when there is none', () => {
    expect(loginErrorMessage(new Error('Too many requests.'))).toBe('Too many requests.');
    expect(loginErrorMessage(null)).toBe('Could not sign in. Try again.');
  });
});

describe('pocketbaseErrorField', () => {
  it('returns the first flagged field', () => {
    const err = { response: { data: { email: { code: 'validation_invalid_email', message: 'Invalid' } } } };
    expect(pocketbaseErrorField(err)).toBe('email');
  });

  it('returns null without field errors', () => {
    expect(pocketbaseErrorField(new Error('x'))).toBeNull();
    expect(pocketbaseErrorField({ response: { data: {} } })).toBeNull();
  });
});
