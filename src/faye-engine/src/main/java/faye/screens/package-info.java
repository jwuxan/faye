/**
 * The typed screens of screen protocol v1 ({@code src/schema/screen.schema.json}) as {@link
 * faye.engine.ScreenSpec}s: what each screen's payload holds and which answers it takes. The engine
 * knows none of them; an embedder registers these, or its own, in a {@link
 * faye.engine.ScreenRegistry}. Credential material on a screen comes from the {@link
 * faye.engine.CredentialIssuer} of the module that verifies it, never from here.
 */
@NullMarked
package faye.screens;

import org.jspecify.annotations.NullMarked;
