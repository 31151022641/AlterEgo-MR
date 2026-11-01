# Rain and Snow character provenance

The source ZIPs listed below remain local development references and are
excluded from Git. Download them from the official source links when needed;
the converted runtime portrait data and its credits are included in the repository.

Rain Rig © Blender Foundation | https://studio.blender.org

Snow Rig © Blender Foundation | https://studio.blender.org

Both character bases are licensed under [Creative Commons Attribution 4.0 International (CC BY 4.0)](https://creativecommons.org/licenses/by/4.0/). The full license, including its warranty disclaimer, is packaged in [CC-BY-4.0.txt](app/src/main/assets/portraits/CC-BY-4.0.txt). The APK also contains [ATTRIBUTION.txt](app/src/main/assets/portraits/ATTRIBUTION.txt), and the Faces and Settings pages display the credits with source and license links.

| Original character | Official source | Preserved archive | SHA-256 |
| --- | --- | --- | --- |
| Rain | [Blender Studio Rain v3](https://studio.blender.org/characters/rain/v3/) | `assets/candidates/free-humans/Rain-v3.3.zip` | `80217F163F6392DC829233D63C2CFB5E1376775BC34101AD14F39631FEA70D24` |
| Snow | [Blender Studio Snow v4](https://studio.blender.org/characters/snow/v4/) | `assets/candidates/free-humans/Snow_v4.2.zip` | `98D58EF3A07083EDE14140BC462CCCB55D317062846AA0EB652D2E4A7AC422A6` |

Rain's ZIP contains `Rain v3.3/rain_v3.2.blend`; the differing internal filename is preserved from the original download. Snow's ZIP contains `Snow/snow_v4.2.blend`. The source archives are development references; the app packages converted head data rather than the full Blender scenes.

The AlterEgo MR adaptations extract the head, evaluate and optimize the geometry, triangulate it, convert its coordinates, and convert materials to opaque vertex colors and surface finishes. Facial-rig samples become expression and gaze morphs. Optional palettes and hairstyle visibility are app adaptations. Rain supplies Rain Portrait and Holographic Matrix Human; Snow supplies Snow Portrait, Classical Golden Bust and Cyber-Synth Human. The stable saved preset identifiers remain unchanged.

The character data and source artwork retain their CC BY 4.0 attribution. These adaptations are distinct from the original Blender production rigs. Blender Foundation and Blender Studio do not endorse this application. The license does not grant trademark rights or change the licenses of other application components.

The source evaluation sheet in `assets/previews/free-face-candidates.png` uses the original official preview images, identified as source artwork. It is not an application render. Its source images and their URLs remain recorded in [the candidate provenance](assets/candidates/free-humans/README.md).
