import XCTest

final class PracticeUITests:XCTestCase {
    func testModesTargetSelectionAndScoreNavigation() {
        let app=XCUIApplication(); app.launch()
        XCTAssertTrue(app.staticTexts["Intonation Trainer"].waitForExistence(timeout:10))
        app.buttons.matching(NSPredicate(format:"label CONTAINS %@","Free tuning")).firstMatch.tap()
        app.buttons["Selected note"].tap()
        XCTAssertTrue(app.staticTexts["Successful holds: 0"].exists)
        app.buttons.matching(NSPredicate(format:"label CONTAINS %@","A4")).firstMatch.tap()
        app.buttons["B4"].tap()
        XCTAssertTrue(app.buttons.matching(NSPredicate(format:"label CONTAINS %@","B4")).firstMatch.exists)
        attach(app,"selected-note")
        app.buttons.matching(NSPredicate(format:"label CONTAINS %@","Selected note")).firstMatch.tap()
        app.buttons["Follow score"].tap()
        XCTAssertTrue(app.staticTexts["Next: C4 · 1/8"].exists)
        app.buttons["Next"].tap()
        XCTAssertTrue(app.staticTexts["Next: D4 · 2/8"].exists)
        app.buttons["Restart"].tap()
        XCTAssertTrue(app.staticTexts["Next: C4 · 1/8"].exists)
        attach(app,"score-following")
    }
    private func attach(_ app:XCUIApplication,_ name:String) {
        let attachment=XCTAttachment(screenshot:app.screenshot()); attachment.name=name; attachment.lifetime = .keepAlways; add(attachment)
    }
}
