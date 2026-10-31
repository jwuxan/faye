/** WebAuthn ceremonies for the passkey screens, driven by @simplewebauthn/browser (ADR 0022). */
import {
  browserSupportsWebAuthnAutofill,
  startAuthentication,
  startRegistration,
  WebAuthnAbortService,
} from "@simplewebauthn/browser";

/** The credential as JSON, posted to the server (the library's own return type). */
export type CredentialJson = Record<string, unknown>;

const asJson = (credential: object): CredentialJson => credential as CredentialJson;

/** Whether the browser offers passkeys in the identifier field's autofill. */
export async function autofillAvailable(): Promise<boolean> {
  return browserSupportsWebAuthnAutofill();
}

/**
 * Asks for any passkey of this site. With conditional mediation the request waits silently until
 * the user picks a passkey from autofill. Resolves null if aborted or dismissed.
 */
export async function getPasskey(
  challenge: string,
  options: { conditional?: boolean } = {},
): Promise<CredentialJson | null> {
  try {
    return asJson(await startAuthentication({
      optionsJSON: { challenge, userVerification: "preferred" },
      useBrowserAutofill: options.conditional === true,
    }));
  } catch {
    return null;
  }
}

/** Cancels the pending ceremony: the silent autofill request on unmount, or before another starts. */
export function cancelPasskeyCeremony(): void {
  WebAuthnAbortService.cancelCeremony();
}

/** Creates a discoverable passkey for the signed in account. Resolves null if dismissed. */
export async function createPasskey(
  challenge: string,
  register: { userId: string; userName: string; displayName: string; algorithms: number[] },
): Promise<CredentialJson | null> {
  try {
    return asJson(await startRegistration({
      optionsJSON: {
        challenge,
        rp: { name: "Faye" },
        user: { id: register.userId, name: register.userName, displayName: register.displayName },
        pubKeyCredParams: register.algorithms.map((alg) => ({ type: "public-key" as const, alg })),
        authenticatorSelection: { residentKey: "required", userVerification: "preferred" },
        attestation: "none",
      },
    }));
  } catch {
    return null;
  }
}
