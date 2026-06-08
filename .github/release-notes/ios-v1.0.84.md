## Highlights

- **Radio config now accepts fractional kHz bandwidth (#21).** The **BW (kHz)** field in Settings → Radio config previously rejected sub-kHz values such as `62.5` — the field refused the decimal point and parsed the entry as a whole number, so common narrow-band settings like 62.5 kHz couldn't be entered. The unit stays **kHz**; the field now allows a decimal point, parses the value as a decimal and rounds to Hz on save (`62.5 → 62500 Hz`), and renders the saved value back with fractional precision (`62.5`, while whole values still show as `500`). The radio-on log line no longer truncates fractional bandwidth via integer division.

## What didn't change

- No wire-format, protocol, or message-handling changes since `ios-v1.0.82`. The shared engine is byte-identical; this is a UI/input-validation fix confined to the radio-config form.
