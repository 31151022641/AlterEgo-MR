# AlterEgo MR release signing

The Quest app is `com.misterslee.alterego.mr`, App ID `1331229656743286`.
Run `build_quest_release.ps1` to create a signed release APK and run its unit
checks. `-Offline` works once Android's required build/check dependencies are cached.

The private key is `signing/alterego-release.p12`, alias `alterego`. Its randomly
generated password is encrypted using Windows DPAPI in `signing/password.dpapi`.
It can be decrypted only by this Windows account in its current Windows setup.
The password is passed to the build through a temporary process environment
variable and restored/cleared afterward. Neither signing file goes into Git,
the app, or the Windows webcam receiver.

Keep the original key and a recoverable password backup securely. Copying the
encrypted DPAPI password file alone to another Windows account is not a portable
backup. Arrange password recovery while the current Windows account is still
available before moving build machines. Do not share either private signing
file in chat. Every future release for this package must use the same key.

The older development app, `com.oculus.camerademo`, remains a separate installation
using Android's debug certificate. The new release package can be installed
alongside it, avoiding deletion of the earlier app's settings. The installation
script now defaults to the signed release and uses fully qualified activity
names. `-DebugBuild` is available for development but has a different certificate;
Android will reject installing it over a release APK with the same package.

First signed release: version 1.0.1, code 2. Android verifier confirmed v2 signing.
Certificate SHA-256: `d519417f860f9220f03bf7eef0c62f11f7e8bf4d4407c016da0da9b9c028ffca`.
