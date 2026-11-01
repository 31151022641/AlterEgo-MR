import SwiftUI
import AppKit

private let ink = Color(red: 0.91, green: 0.94, blue: 0.96)
private let muted = Color(red: 0.65, green: 0.74, blue: 0.81)
private let accent = Color(red: 0.45, green: 0.86, blue: 0.88)
private let panel = Color(red: 0.105, green: 0.15, blue: 0.20)

struct WebcamWindow: View {
    @ObservedObject var model: WebcamModel
    @State private var showReset = false
    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            HStack(spacing: 14) {
                Image(nsImage: NSImage(named: NSImage.applicationIconName) ?? NSImage())
                    .resizable().frame(width: 48, height: 48)
                VStack(alignment: .leading, spacing: 4) {
                    Text("AlterEgo MR Webcam").font(.system(size: 25, weight: .semibold))
                    Text("Your Quest avatar, ready for your Mac calls").foregroundStyle(muted)
                }
                Spacer()
                Label(model.preview != nil ? "Video live" : model.running ? "Waiting for Quest" : "Receiver off",
                      systemImage: model.preview != nil ? "video.fill" : "video.slash")
                    .font(.callout.weight(.medium)).padding(10)
                    .background(accent.opacity(0.10), in: RoundedRectangle(cornerRadius: 10))
            }
            HStack(alignment: .top, spacing: 20) {
                VStack(alignment: .leading, spacing: 12) {
                    HStack { Text("Live preview").font(.headline); Spacer(); Text(model.resolution).foregroundStyle(muted).monospacedDigit() }
                    ZStack {
                        RoundedRectangle(cornerRadius: 16).fill(Color(red: 0.04, green: 0.07, blue: 0.10))
                        if let image = model.preview {
                            Image(nsImage: image).resizable().aspectRatio(contentMode: .fit).padding(8)
                        } else {
                            VStack(spacing: 15) {
                                Image(systemName: "person.crop.rectangle").font(.system(size: 52, weight: .light)).foregroundStyle(accent)
                                Text(model.running ? "Waiting for your Quest mirror" : "Your avatar will appear here")
                                    .font(.title3.weight(.medium))
                                Text("Start the receiver, then connect from\nMy mirror → Menu → PC Link → Find PC on Quest.")
                                    .foregroundStyle(muted).multilineTextAlignment(.center).lineSpacing(5)
                            }.padding(26)
                        }
                    }.frame(maxWidth: .infinity, maxHeight: .infinity)
                    Text("In your call app, select AlterEgo MR Avatar. Use your Mac microphone for audio.")
                        .font(.callout).foregroundStyle(muted)
                    Text(model.status).font(.callout).textSelection(.enabled)
                }.frame(maxWidth: .infinity, maxHeight: .infinity)
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        card("Quest connection", icon: "wifi") {
                            Picker("Network", selection: $model.selectedAddress) {
                                ForEach(model.networks) { choice in Text(choice.title).tag(choice.address) }
                            }.disabled(model.running || model.busy)
                            if model.networks.isEmpty { Text("Connect your Mac and Quest to the same Wi-Fi network.").foregroundStyle(muted) }
                            Button("Refresh networks", action: model.refreshNetworks).disabled(model.running || model.busy)
                            Button(action: model.toggleReceiver) {
                                HStack { Spacer(); if model.busy { ProgressView().controlSize(.small) }; Text(model.running ? "Stop receiver" : "Start receiver"); Spacer() }
                            }.buttonStyle(.borderedProminent).tint(accent).controlSize(.large)
                                .disabled(model.busy || !model.ready || model.selectedAddress.isEmpty)
                            DisclosureGroup("Show manual pairing") {
                                VStack(alignment: .leading, spacing: 9) {
                                    Text("Mac address").font(.caption).foregroundStyle(muted)
                                    HStack { Text(model.selectedAddress + ":38991").textSelection(.enabled); Spacer(); Button("Copy", action: model.copyAddress) }
                                    Text("Full pairing key").font(.caption).foregroundStyle(muted)
                                    Text(model.pairingKey).font(.system(.caption, design: .monospaced)).textSelection(.enabled).lineLimit(nil)
                                    Button("Copy pairing key", action: model.copyKey).disabled(model.pairingKey.isEmpty)
                                    Text("The full key authenticates this Mac. Never share it publicly.").font(.caption).foregroundStyle(muted)
                                }.padding(.top, 8)
                            }
                        }
                        card("Background", icon: "photo") {
                            Text(model.backgroundSettings.style.title).font(.headline)
                            Text(model.backgroundNotice).font(.callout).foregroundStyle(muted)
                            Button("Change background…") { model.showBackground = true }.disabled(!model.ready)
                        }
                        CameraTools(camera: model.camera, check: model.checkCamera, disabled: model.running || model.busy)
                        DisclosureGroup("More options") {
                            Button("Reset pairing…") { showReset = true }.disabled(model.running || model.busy)
                            Text("Pairing and background choices stay on this Mac. Closing the app clears camera output.")
                                .font(.caption).foregroundStyle(muted).padding(.top, 6)
                        }.padding(14)
                    }.padding(.trailing, 4)
                }.frame(width: 340)
            }
        }.padding(24).frame(minWidth: 920, minHeight: 640)
            .background(Color(red: 0.065, green: 0.095, blue: 0.13)).foregroundStyle(ink)
            .sheet(item: $model.pairing) { prompt in
                VStack(spacing: 20) {
                    Image(systemName: "lock.shield").font(.system(size: 40)).foregroundStyle(accent)
                    Text("Pair your Quest").font(.title2.weight(.semibold))
                    Text("Confirm that this exact code appears on your Quest.").foregroundStyle(muted)
                    Text(prompt.code).font(.system(size: 34, weight: .semibold, design: .monospaced)).textSelection(.enabled)
                    Text("Approve on both devices only when the codes match.").foregroundStyle(muted)
                    HStack { Button("Cancel") { model.respondToPairing(false, id: prompt.id) }; Button("Codes match") { model.respondToPairing(true, id: prompt.id) }.buttonStyle(.borderedProminent).tint(accent) }
                }.padding(32).frame(minWidth: 510)
                    .onDisappear { model.respondToPairing(false, id: prompt.id) }
            }
            .sheet(isPresented: $model.showBackground) { BackgroundWindow(model: model) }
            .alert("AlterEgo MR", isPresented: Binding(get: { model.errorMessage != nil }, set: { if !$0 { model.errorMessage = nil } })) {
                Button("OK", role: .cancel) { model.errorMessage = nil }
            } message: { Text(model.errorMessage ?? "") }
            .confirmationDialog("Reset pairing? Your Quest will need to pair with this Mac again.", isPresented: $showReset) {
                Button("Reset pairing", role: .destructive, action: model.resetPairing)
            }
    }
}

@ViewBuilder private func card<Content: View>(_ title: String, icon: String, @ViewBuilder content: () -> Content) -> some View {
    VStack(alignment: .leading, spacing: 12) {
        Label(title, systemImage: icon).font(.headline).foregroundStyle(accent)
        content()
    }.padding(16).frame(maxWidth: .infinity, alignment: .leading)
        .background(panel, in: RoundedRectangle(cornerRadius: 14))
}

private struct CameraTools: View {
    @ObservedObject var camera: CameraManager
    let check: () -> Void
    let disabled: Bool
    var body: some View {
        card("Mac camera", icon: "video") {
            Text(camera.status).font(.callout).foregroundStyle(muted)
            Button("Enable camera", action: camera.install).disabled(disabled || camera.busy)
            Button("Check camera", action: check).disabled(disabled || camera.busy)
            Text("macOS will ask you to approve the camera extension. This app does not use your Mac's physical camera.")
                .font(.caption).foregroundStyle(muted)
            DisclosureGroup("Remove camera") {
                Button("Disable camera extension", role: .destructive, action: camera.uninstall).disabled(disabled || camera.busy)
            }
        }
    }
}

private struct BackgroundWindow: View {
    @ObservedObject var model: WebcamModel
    @Environment(\.dismiss) private var dismiss
    @State private var settings = AvatarBackground.Settings()
    @State private var pictureURL: URL?
    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            Text("Choose your background").font(.title2.weight(.semibold))
            Text("Applies to your preview and Mac webcam.").foregroundStyle(muted)
            ScrollView { VStack(alignment: .leading, spacing: 16) {
            Picker("Background style", selection: $settings.style) {
                ForEach(AvatarBackground.Style.allCases) { style in Text(style.title).tag(style) }
            }
            if settings.style == .colour {
                ColorPicker("Colour", selection: colour, supportsOpacity: false)
            }
            Button("Choose PNG/JPG…") {
                model.choosePicture { url in pictureURL = url; settings.style = .picture }
            }
            if let pictureURL = pictureURL { Text(pictureURL.lastPathComponent).font(.caption).lineLimit(1) }
            Text("Edge strength: \(settings.strength)")
            Slider(value: Binding(get: { Double(settings.strength) }, set: { settings.strength = Int($0) }), in: 12...60, step: 1)
            Text("Replaces plain avatar backdrops. Detailed scenes may keep their original background.")
                .font(.callout).foregroundStyle(muted)
            }.padding(.trailing, 4) }
            HStack { Spacer(); Button("Cancel") { dismiss() }; Button("Apply") { model.updateBackground(settings, pictureURL: pictureURL); dismiss() }.buttonStyle(.borderedProminent).tint(accent) }
        }.padding(26).frame(width: 490, height: 510)
            .onAppear { settings = model.backgroundSettings }
            .onChange(of: model.backgroundSettings) { settings = $0 }
    }

    private var colour: Binding<Color> {
        Binding(get: {
            let rgb = UInt32(settings.colourHex.dropFirst(), radix: 16) ?? 0x26405C
            return Color(red: Double((rgb >> 16) & 255)/255, green: Double((rgb >> 8) & 255)/255,
                         blue: Double(rgb & 255)/255)
        }, set: { value in
            guard let rgb = NSColor(value).usingColorSpace(.sRGB) else { return }
            settings.colourHex = String(format: "#%02X%02X%02X", Int((rgb.redComponent*255).rounded()),
                                       Int((rgb.greenComponent*255).rounded()), Int((rgb.blueComponent*255).rounded()))
        })
    }
}
