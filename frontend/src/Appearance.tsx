import { useId } from 'react'
import { THEMES, useTheme, type Theme } from './theme'

/** Each theme's name and line (D-499). */
export const THEME_TEXT: Record<Theme, { name: string; line: string }> = {
  blue: { name: 'Blue', line: 'Dark navy, the look the app was designed in' },
  light: { name: 'Light', line: 'White panels on a pale grey page' },
  dark: { name: 'Dark', line: 'Neutral graphite, no blue tint' },
}

/**
 * Settings' Appearance (D-499): the theme, a radio group of three cards, each with a small preview drawn in that theme's
 * own tokens (its `data-theme` on the preview). Choosing applies at once: no Save button and no message.
 */
export default function Appearance() {
  const { theme, setTheme } = useTheme()
  const heading = useId()
  const note = useId()
  return (
    <section className="card appearance">
      <h3 id={heading}>Theme</h3>
      <div role="radiogroup" aria-labelledby={heading} aria-describedby={note} className="theme-options">
        {THEMES.map((option) => (
          <label key={option} className="theme-option">
            <input type="radio" name="theme" value={option} checked={theme === option}
              onChange={() => setTheme(option)} />
            <span className="theme-preview" data-theme={option} aria-hidden="true">
              <span className="swatch income" />
              <span className="swatch expense" />
              <span className="swatch accent" />
            </span>
            <span className="theme-name">{THEME_TEXT[option].name}</span>
            <span className="theme-line">{THEME_TEXT[option].line}</span>
          </label>
        ))}
      </div>
      <p id={note} className="muted small">
        Saved in this browser. Until you choose, the app follows your device: Light when it is set to light, Blue when
        it is set to dark.
      </p>
    </section>
  )
}
