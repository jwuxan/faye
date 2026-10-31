/** WebAuthn ceremonies for the passkey screens. Credentials are posted as JSON with base64url fields. */

const encode = (buffer: ArrayBuffer): string =>
  btoa(String.fromCharCode(...new Uint8Array(buffer))).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");

const decode = (text: string): ArrayBuffer => {
  const binary = atob(text.replace(/-/g, "+").replace(/_/g, "/"));
  return Uint8Array.from(binary, (c) => c.charCodeAt(0)).buffer;
};

export type CredentialJson = Record<string, unknown>;

function toJson(credential: PublicKeyCredential): CredentialJson {
  const r = credential.response;
  const response: Record<string, unknown> = { clientDataJSON: encode(r.clientDataJSON) };
  if (r instanceof AuthenticatorAttestationResponse) {
    response.attestationObject = encode(r.attestationObject);
    response.transports = r.getTransports?.() ?? [];
  } else if (r instanceof AuthenticatorAssertionResponse) {
    response.authenticatorData = encode(r.authenticatorData);
    response.signature = encode(r.signature);
    if (r.userHandle) response.userHandle = encode(r.userHandle);
  }
  return {
    id: credential.id,
    rawId: encode(credential.rawId),
    type: credential.type,
    response,
    clientExtensionResults: credential.getClientExtensionResults(),
  };
}

/** Whether the browser offers passkeys in the identifier field's autofill. */
export async function autofillAvailable(): Promise<boolean> {
  return typeof PublicKeyCredential !== "undefined"
    && (await PublicKeyCredential.isConditionalMediationAvailable?.()) === true;
}

/**
 * Asks for any passkey of this site. With conditional mediation the request waits silently until
 * the user picks a passkey from autofill. Resolves null if aborted or dismissed.
 */
export async function getPasskey(
  challenge: string,
  options: { conditional?: boolean; signal?: AbortSignal } = {},
): Promise<CredentialJson | null> {
  try {
    const credential = await navigator.credentials.get({
      mediation: options.conditional ? "conditional" : "optional",
      signal: options.signal,
      publicKey: { challenge: decode(challenge), userVerification: "preferred" },
    });
    return credential instanceof PublicKeyCredential ? toJson(credential) : null;
  } catch {
    return null;
  }
}

/** Creates a discoverable passkey for the signed in account. Resolves null if dismissed. */
export async function createPasskey(
  challenge: string,
  user: { userId: string; userName: string; displayName: string },
): Promise<CredentialJson | null> {
  try {
    const credential = await navigator.credentials.create({
      publicKey: {
        challenge: decode(challenge),
        rp: { name: "Faye" },
        user: { id: decode(user.userId), name: user.userName, displayName: user.displayName },
        // ES256, then RS256: what the server accepts
        pubKeyCredParams: [
          { type: "public-key", alg: -7 },
          { type: "public-key", alg: -257 },
        ],
        authenticatorSelection: { residentKey: "required", userVerification: "preferred" },
        attestation: "none",
      },
    });
    return credential instanceof PublicKeyCredential ? toJson(credential) : null;
  } catch {
    return null;
  }
}
