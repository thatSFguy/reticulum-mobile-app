// SPDX-License-Identifier: MIT
//
// Keyboard-dismissal helpers. SwiftUI's TextField doesn't auto-dismiss
// when the user taps outside of it — the system keyboard sits there
// until something explicitly resigns first responder, the user
// dismisses via the system gesture, or the field's view goes away.
// That's surprising to almost everyone (UIKit's UITableView used to
// auto-dismiss; SwiftUI dropped the convenience). We close the gap
// by:
//
//   1. Wrapping every scrollable container in `.scrollDismissesKeyboard
//      (.immediately)` so swiping the list / form area dismisses
//      the keyboard the way users expect.
//   2. Adding a "Done" button to the keyboard toolbar for views with
//      multi-line input (the conversation draft TextField doesn't
//      submit on Return because Return is a newline) so the user has
//      a one-tap way out.
//   3. Exposing `dismissKeyboard()` for any other manual-resign sites.
//
// Sending UIResponder.resignFirstResponder up the chain to nil is the
// canonical SwiftUI workaround — see Apple's WWDC session "What's new
// in SwiftUI 2020" Q&A. Any active first responder receives it and
// resigns; if there is none, it's a no-op.

import SwiftUI
import UIKit

/// Globally dismiss whichever first responder is currently up. Safe
/// to call from anywhere on the main thread; no-ops if no field is
/// focused. Use from keyboard-toolbar "Done" buttons and any other
/// imperative-dismiss site.
@MainActor
func dismissKeyboard() {
    UIApplication.shared.sendAction(
        #selector(UIResponder.resignFirstResponder),
        to: nil,
        from: nil,
        for: nil
    )
}

extension View {
    /// Attach to a view containing a multi-line TextField that needs
    /// an explicit dismiss affordance (Return inserts a newline, so
    /// the user can't get out via the keyboard's submit key). Adds a
    /// "Done" button on the right side of the keyboard toolbar.
    /// Spacer pushes Done to the trailing edge to match Apple's
    /// system convention for input-accessory toolbars.
    func keyboardDoneToolbar() -> some View {
        toolbar {
            ToolbarItemGroup(placement: .keyboard) {
                Spacer()
                Button("Done") { dismissKeyboard() }
            }
        }
    }
}
