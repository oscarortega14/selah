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
        tap("3. Refugio")
        shot("4 Horizontal")
    }
    /// Capturas para la página web (oortega14.com/apps/selah), en iPhone y conectado a un Selah de verdad:
    /// PhoneE2ETool en el puerto 8765 (SELAH_E2E_ROLE=OPERATOR SELAH_E2E_NAME=PC-IGLESIA) con una carpeta de ejemplo.
    func testCapturasWeb() {
        continueAfterFailure = true
        XCUIDevice.shared.orientation = .portrait
        app.launch()
        sleep(5)
        // La app encuentra sola el computador de la red (el servidor de prueba en esta Mac) y se conecta.
        // El servidor de prueba aprueba el celular como operador en menos de un segundo.
        for _ in 0..<20 where element("Esperando que te den acceso").exists { sleep(1) }
        if element("Soy el operador").exists { tap("Soy el operador") }
        if element("✕").exists { tap("✕") }

        tap("Canciones")
        shot("05-canciones")
        tap("Buscar por título o por la letra")
        app.typeText("Tu luz")
        sleep(1)
        tap("Tu luz en mí")
        tap("En pantalla")
        if element("Coro").exists { tap("Coro") }
        // Al tocar "Coro" la letra se desplaza un poco: se vuelve arriba arrastrando sin tocar ninguna parte.
        let from = app.coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.55))
        from.press(forDuration: 0.2, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.8)))
        sleep(1)
        shot("01-cancion-en-pantalla")

        tap("Lista")
        shot("02-lista-del-culto")
        tap("Domingo")
        shot("07-culto-del-domingo")
        tap("3. Refugio")
        shot("08-cancion-sin-proyectar")

        tap("Biblia")
        tap("Cita o frase (ej. Juan 3:16 · amó Dios)")
        app.typeText("Juan 3:16")
        sleep(1)
        tap("Mostrar") // proyecta y pasa a "En pantalla"
        sleep(1)
        shot("04-versiculo-en-pantalla")
        tap("Biblia")
        shot("03-biblia-juan-3")

        element("Lista").swipeLeft()
        sleep(1)
        tap("Más")
        shot("06-mas-opciones")
    }
}
