import SwiftUI
import WidgetKit

@main
struct FoodBlobWidgetsBundle: WidgetBundle {
  var body: some Widget {
    if #available(iOS 17.0, *) {
      FoodCounterWidget(fixedSkin: .skyMeadow)
      FoodCounterWidget(fixedSkin: .shrine)
    }
  }
}
