import SwiftUI

struct PairView: View {
    @EnvironmentObject private var state: AppState
    @State private var confirmReset = false
    @State private var copied = false

    var body: some View {
        Form {
            Section {
                HStack(alignment: .top, spacing: 28) {
                    qr
                    VStack(alignment: .leading, spacing: 14) {
                        Text("Pair your Android phone").font(.title2.weight(.semibold))
                        Step(n: 1, text: "No app yet? Scan this code with your phone's camera to get Mac n Droid from Google Play.")
                        Step(n: 2, text: "Open Mac n Droid and tap **Scan QR code**.")
                        Step(n: 3, text: "Point your phone at this code.")
                        Text("Both devices need to be on the same Wi-Fi network. Everything stays on your network and is end-to-end encrypted.")
                            .font(.callout)
                            .foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .padding(.vertical, 8)
            }

            Section("Connection") {
                LabeledContent("This Mac", value: Net.computerName)
                LabeledContent("Address", value: state.addresses.isEmpty ? "No network" : state.addresses.joined(separator: ", "))
                LabeledContent("Port", value: state.port == 0 ? "—" : String(state.port))
                LabeledContent("Status") {
                    if state.isConnected, let phone = state.phone {
                        Label("Connected to \(phone.name)", systemImage: "checkmark.circle.fill")
                            .foregroundStyle(.green)
                    } else {
                        Text("Waiting for phone").foregroundStyle(.secondary)
                    }
                }
            }

            Section {
                Button(copied ? "Copied" : "Copy Pairing Link") {
                    state.copyToMac(state.pairingURI)
                    copied = true
                    DispatchQueue.main.asyncAfter(deadline: .now() + 2) { copied = false }
                }
                Button("Reset Pairing…", role: .destructive) { confirmReset = true }
            } footer: {
                Text("Resetting creates a new code. Phones paired with the old code are disconnected and must scan again.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .formStyle(.grouped)
        .navigationTitle("Pair a Phone")
        .confirmationDialog("Reset pairing?", isPresented: $confirmReset) {
            Button("Reset Pairing", role: .destructive) { state.resetPairing() }
        } message: {
            Text("All paired phones will need to scan the new QR code.")
        }
    }

    /// The code is a Google Play link, so a phone without the app goes to its store page. The pairing details
    /// follow the "#" (a fragment, never included in web requests); the app reads them from there.
    static let playLink = "https://play.google.com/store/apps/details?id=app.macndroid#"

    private var qr: some View {
        Group {
            if let img = QRCode.image(for: Self.playLink + state.pairingURI), !state.pairingURI.isEmpty {
                Image(nsImage: img)
                    .interpolation(.none)
                    .resizable()
                    .scaledToFit()
                    .padding(12)
                    .background(.white, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            } else {
                ProgressView()
            }
        }
        .frame(width: 220, height: 220)
        .shadow(color: .black.opacity(0.08), radius: 6, y: 2)
    }
}

private struct Step: View {
    let n: Int
    let text: LocalizedStringKey

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            Text("\(n)")
                .font(.callout.weight(.bold))
                .foregroundStyle(.white)
                .frame(width: 22, height: 22)
                .background(Color.accentColor, in: Circle())
            Text(text)
        }
    }
}
