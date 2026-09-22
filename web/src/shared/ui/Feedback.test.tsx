import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { Field } from './Feedback'

describe('Field', () => {
  it('associates a backend field error with the control', () => {
    render(
      <Field id="email" label="Email" error="must be a well-formed email address">
        <input />
      </Field>,
    )
    const input = screen.getByRole('textbox', { name: /Email/ })
    expect(input).toHaveAttribute('aria-invalid', 'true')
    expect(input).toHaveAttribute('aria-describedby', 'email-error')
    expect(document.getElementById('email-error')).toHaveTextContent('must be a well-formed email address')
  })
})
