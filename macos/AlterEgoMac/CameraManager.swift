import Foundation
import SystemExtensions
import Combine

final class CameraManager: NSObject, ObservableObject, OSSystemExtensionRequestDelegate {
    @Published var status = "Camera setup needed"
    @Published var busy = false
    var onChanged: (() -> Void)?

    func install() {
        guard !busy else { return }
        guard Bundle.main.bundleURL.deletingLastPathComponent().path == "/Applications" else {
            status = "Move AlterEgo MR Webcam to Applications, then choose Enable camera."
            return
        }
        busy = true; status = "Requesting the macOS camera extension…"
        let request = OSSystemExtensionRequest.activationRequest(
            forExtensionWithIdentifier: ProductConfiguration.cameraExtensionBundleIdentifier, queue: .main)
        request.delegate = self; OSSystemExtensionManager.shared.submitRequest(request)
    }
    func uninstall() {
        guard !busy else { return }
        busy = true; status = "Removing the camera extension…"
        let request = OSSystemExtensionRequest.deactivationRequest(
            forExtensionWithIdentifier: ProductConfiguration.cameraExtensionBundleIdentifier, queue: .main)
        request.delegate = self; OSSystemExtensionManager.shared.submitRequest(request)
    }
    func requestNeedsUserApproval(_ request: OSSystemExtensionRequest) {
        status = "Approve AlterEgo's camera extension in System Settings, then return here."
    }
    func request(_ request: OSSystemExtensionRequest, actionForReplacingExtension existing: OSSystemExtensionProperties,
                 withExtension ext: OSSystemExtensionProperties) -> OSSystemExtensionRequest.ReplacementAction { .replace }
    func request(_ request: OSSystemExtensionRequest, didFinishWithResult result: OSSystemExtensionRequest.Result) {
        busy = false
        status = result == .willCompleteAfterReboot ? "Restart your Mac to complete camera setup." : "Camera extension request completed"
        if result == .completed { onChanged?() }
    }
    func request(_ request: OSSystemExtensionRequest, didFailWithError error: Error) {
        busy = false; status = error.localizedDescription
    }
}
