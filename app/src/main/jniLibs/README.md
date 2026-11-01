Pinned official Meta SDK arm64 runtime libraries:

- `libovrplatformloader.so`: Meta Platform SDK 69.0.0 (Avatars' pinned dependency).
- `libovravatar2.so`: Meta Avatars SDK 40.0.1, native ABI 40.0.0.24.4.

Downloaded from Meta's public package registry, with SHA-1 checked against its
package metadata. Their license notices are packaged in `assets/licenses`.
The native account bridge uses approved headset account requests and does not
embed credentials. It verifies entitlement before requesting identity/token.
Avatar access verification is separate from avatar mesh rendering.
The SDK entity and custom GLES renderer are implemented and verified with the
official public sample. Personal avatar access still requires Meta approval;
see META_AVATAR_INTEGRATION.md for the verified boundary and remaining work.
The bundled core and sample assets use the same Meta SDK license; their package
metadata explicitly permits sample presets in developers' apps.
