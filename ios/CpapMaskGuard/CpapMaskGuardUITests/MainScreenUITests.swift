import XCTest

final class MainScreenUITests: XCTestCase {
    func testLifestyleHomeAndFillModes() {
        let app = XCUIApplication()
        app.launch()

        XCTAssertTrue(app.staticTexts["还没开始"].waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertTrue(app.buttons["开始守护"].exists)
        XCTAssertTrue(app.staticTexts["iOS 不能在锁屏或后台开着相机。只有亮着屏、并且停在这个应用里，才会拍照、看面罩、响铃。锁屏或切到别的应用，相机会停，铃声也会停。"].exists)

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

    func testSettingsRoutesStayUpAndBurnInIsGone() {
        let app = XCUIApplication()
        app.launch()

        let settings = app.buttons["设置与本机"]
        XCTAssertTrue(settings.waitForExistence(timeout: 8), app.debugDescription)
        settings.tap()

        XCTAssertFalse(app.buttons["使用前检查本机"].exists)
        XCTAssertFalse(app.staticTexts["使用前检查本机"].exists)
        XCTAssertFalse(app.buttons["Phone check before use"].exists)

        openRoute(app, "settings.route.status", "本机状态", expect: "监测健康分")
        openRoute(app, "settings.route.baseline", "拍几张戴着面罩的样子", expect: "不会打开相机")
        openRoute(app, "settings.route.logs", "查看日志", expect: "打开了面罩守护")

        app.swipeUp()
        XCTAssertFalse(app.buttons["使用前检查本机"].exists)
        XCTAssertFalse(app.staticTexts["使用前检查本机"].exists)
    }

    private func openRoute(_ app: XCUIApplication, _ identifier: String, _ title: String, expect: String) {
        var button = app.buttons[identifier]
        if !button.waitForExistence(timeout: 2) || !button.isHittable {
            app.swipeUp()
            button = app.buttons[identifier]
        }
        if !button.exists {
            let settings = app.buttons["设置与本机"]
            if settings.exists {
                settings.tap()
            }
            if !app.buttons[identifier].waitForExistence(timeout: 2) {
                app.swipeUp()
            }
            button = app.buttons[identifier]
        }
        XCTAssertTrue(button.waitForExistence(timeout: 4), app.debugDescription)
        XCTAssertTrue(app.staticTexts[title].exists || app.buttons[title].exists, app.debugDescription)
        button.tap()

        let marker = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", expect)).firstMatch
        XCTAssertTrue(marker.waitForExistence(timeout: 4), app.debugDescription)

        let titledBack = app.navigationBars.buttons["面罩守护"]
        if titledBack.waitForExistence(timeout: 2) {
            titledBack.tap()
        } else {
            let back = app.navigationBars.buttons.firstMatch
            XCTAssertTrue(back.waitForExistence(timeout: 2), app.debugDescription)
            back.tap()
        }
        XCTAssertTrue(app.buttons["设置与本机"].waitForExistence(timeout: 4), app.debugDescription)
    }
}