package fi.palonkorpi.sidehome.security

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent

/**
 * The phone's own screen lock, asked for by the Locked shelf. [KeyguardManager]'s confirm-credential
 * intent opens the PIN / pattern / password screen the user already set — no launcher-owned secret,
 * no new permission, and nothing stored. Fingerprint and face are not accepted on that screen: the
 * shelf is "behind your password", as asked.
 *
 * Deprecated in favour of `BiometricPrompt`, deliberately not used: the prompt needs the
 * `USE_BIOMETRIC` permission even for a credential-only check, and this launcher's whole privacy
 * story is its three permissions. The keyguard intent still works on current Android and asks for
 * nothing.
 */
fun deviceIsSecure(context: Context): Boolean = keyguard(context)?.isDeviceSecure == true

/** An intent to confirm the device credential, or null when no screen lock is set. Launch it for a
 *  result: `RESULT_OK` means Android confirmed the credential; anything else is a no. */
@Suppress("DEPRECATION")
fun confirmDeviceCredentialIntent(context: Context, title: String, description: String): Intent? =
    keyguard(context)?.takeIf { it.isDeviceSecure }?.createConfirmDeviceCredentialIntent(title, description)

private fun keyguard(context: Context): KeyguardManager? =
    context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
