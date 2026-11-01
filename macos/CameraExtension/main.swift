import Foundation
import CoreMediaIO

let cameraProvider = AvatarCameraProviderSource()
CMIOExtensionProvider.startService(provider: cameraProvider.provider)
CFRunLoopRun()
