import XCTest

/// Capturas para la App Store (no es una prueba normal): abre la demostración, recorre pantallas y guarda cada una
/// en la carpeta CAPTURAS_DIR (TEST_RUNNER_CAPTURAS_DIR=… xcodebuild test …).
final class CapturasTests: XCTestCase {
    let app = XCUIApplication()

    private func element(_ label: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", label)).firstMatch
    }

    private func tap(_ label: String) {
        let e = element(label)
        XCTAssertTrue(e.waitForExistence(timeout: 10), "No encontré «\(label)»")
        e.tap()
        sleep(1)
    }

    private func shot(_ name: String) {
        sleep(1)
        let png = XCUIScreen.main.screenshot().pngRepresentation
        if let dir = ProcessInfo.processInfo.environment["CAPTURAS_DIR"] {
            try? png.write(to: URL(fileURLWithPath: dir).appendingPathComponent("\(name).png"))
        }
        let a = XCTAttachment(data: png, uniformTypeIdentifier: "public.png")
        a.name = name; a.lifetime = .keepAlways; add(a)
    }

    func testCapturas() {
        continueAfterFailure = true
        XCUIDevice.shared.orientation = .portrait
        app.launchEnvironment["SELAH_DEMO"] = "1"
        app.launch()
        sleep(3)
        tap("✕")
        shot("1 Canciones")

        tap("Biblia")
        tap("Cita o frase (ej. Juan 3:16 · amó Dios)")
        app.typeText("Juan 3:16")
        sleep(1)
        tap("Mostrar")
        for k in ["Hide keyboard", "Ocultar teclado", "dismiss"] where app.keyboards.buttons[k].exists { app.keyboards.buttons[k].tap(); break }
        sleep(1)
        shot("2 Biblia")

        tap("Lista")
        shot("3 Lista")

        XCUIDevice.shared.orientation = .landscapeLeft
        sleep(2)
        tap("Canciones")
        tap("Refugio")
        shot("4 Horizontal")
    }
}
