import type { ReactNode } from 'react'

// Inline icons for icon buttons (UX-11, D-527): in `currentColor`, hidden from assistive technology, since the button
// or link that holds one carries the name (`aria-label` and `title`). Strokes on a 16-unit grid, as `SharedIcon`.

const Icon = ({ children }: { children: ReactNode }) => (
  <svg aria-hidden="true" focusable="false" viewBox="0 0 16 16" width="1em" height="1em" fill="none"
    stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
    {children}
  </svg>
)

/** A pencil: rename. */
export const PencilIcon = () => (
  <Icon>
    <path d="M10.5 2.5l3 3L5.5 13.5 2 14l.5-3.5z" />
    <path d="M9 4l3 3" />
  </Icon>
)

/** A box with an arrow down: archive. */
export const ArchiveIcon = () => (
  <Icon>
    <path d="M2 6.5h12v7H2z" />
    <path d="M8 1.5v7" />
    <path d="M5.5 6l2.5 2.5L10.5 6" />
  </Icon>
)

/** A box with an arrow up: restore. */
export const RestoreIcon = () => (
  <Icon>
    <path d="M2 6.5h12v7H2z" />
    <path d="M8 10.5v-9" />
    <path d="M5.5 4L8 1.5 10.5 4" />
  </Icon>
)

/** An arrow leaving a box: go elsewhere to change it. */
export const ExternalIcon = () => (
  <Icon>
    <path d="M12.5 9.5v4h-10v-10h4" />
    <path d="M9.5 2.5h4v4" />
    <path d="M13.5 2.5L7.5 8.5" />
  </Icon>
)
