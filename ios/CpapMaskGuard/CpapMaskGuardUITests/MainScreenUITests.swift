import XCTest

final class MainScreenUITests: XCTestCase {
    func testLifestyleHomeAndFillModes() {
        let app = XCUIApplication()
        app.launch()

        XCTAssertTrue(app.staticTexts["还没开始"].waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertTrue(app.buttons["开始守护"].exists)
        XCTAssertTrue(app.staticTexts["这一版只在亮屏、停在前台时工作。锁屏或切到别的应用，补光会停，相机也不会在后台继续开着。"].exists)

        let settings = app.buttons["设置与本机"]
        XCTAssertTrue(settings.waitForExistence(timeout: 4), app.debugDescription)
        settings.tap()

        let front = app.buttons["前摄 + 白屏补光"]
        let rear = app.buttons["后摄 + 自备光源"]
        if !rear.waitForExistence(timeout: 2) {
            app.swipeUp()
        }
        XCTAssertTrue(front.waitForExistence(timeout: 4), app.debugDescription)
        XCTAssertTrue(rear.exists, app.debugDescription)

        rear.tap()
        let torch = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "手电筒档位")).firstMatch
        if !torch.waitForExistence(timeout: 2) {
            app.swipeUp()
        }
        XCTAssertTrue(torch.waitForExistence(timeout: 4), app.debugDescription)
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "setTorchModeOn")).firstMatch.exists)

        front.tap()
        let brightness = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "白屏亮度")).firstMatch
        if !brightness.waitForExistence(timeout: 2) {
            app.swipeUp()
        }
        XCTAssertTrue(brightness.waitForExistence(timeout: 4), app.debugDescription)
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "UIScreen.brightness")).firstMatch.exists)
    }
}