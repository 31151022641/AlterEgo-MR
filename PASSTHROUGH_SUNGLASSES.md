# Separate Sunglasses face — Quest 1.2.20 (46)

In passthrough, open **Menu → Faces & Avatars → Sunglasses**. This selectable item draws
only dark 3D lenses, rounded frames, a bridge and temple arms over the tracked
real face. There is no replacement head, skin, hair, mouth or body in this mesh.

The original Rain, Snow, Unicorn, Lion and Dragon faces no longer receive
automatic sunglasses. Choosing any of those items restores its original head.
Sunglasses has its own saved selection and thumbnail. It is excluded from My
mirror choices; returning to the mirror restores that view's own avatar.

The effect uses the existing calibrated stereo placement, tracked head rotation,
scale and visibility policy. Expressions and blinking do not resize the frame.
Its 1,012 vertices and 1,740 triangles are cached privately, and each renderer
receives independent buffers. No extra tracking, textures or downloaded models
are required.

[The preview](assets/previews/standalone-sunglasses.png) uses actual production
geometry, shading and depth. It shows Sunglasses as a separate item alongside
the five original faces. All six preview cases have valid buffers and indices
and no clipped vertices. These are synthetic previews, not headset captures.

Verified on 28 October 2026:

- Release build succeeded; all 444 JVM tests passed, with no failures, errors
  or skipped tests. New checks cover the separate item, absence of a head,
  original face geometry, expression stability, saved selections and mirror
  exclusion.
- Six production-geometry previews compile and render with zero clipping.
- Required portrait assets and credits remain packaged.
- APK Signature Scheme v2 verified. Package version is 1.2.20, code 46.
- APK size: 141,461,770 bytes.
- SHA-256: `F903BD90FB9CD85E64481B010C631350C43B5BC20DC0BDBFDA864D39C42339D9`.

Installed successfully on the USB-connected Quest Pro. The installed package
reports 1.2.20 (46), and launching passthrough returned `Status: ok`.
The headset remained asleep, so alignment and appearance on a real tracked face
still require an awake headset check.
