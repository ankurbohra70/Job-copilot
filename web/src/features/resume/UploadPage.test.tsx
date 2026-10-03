import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { UploadPage } from './UploadPage'

describe('UploadPage', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('shows backend upload errors without treating client checks as authority', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: false,
        status: 422,
        text: async () =>
          JSON.stringify({
            status: 422,
            error: 'Unprocessable Entity',
            message: 'No meaningful text could be extracted; upload a text-based PDF meeting the configured minimum text length',
          }),
      }),
    )
    const user = userEvent.setup()
    render(
      <MemoryRouter>
        <UploadPage />
      </MemoryRouter>,
    )
    const input = screen.getByLabelText('Resume PDF')
    const file = new File(['%PDF-1.4'], 'resume.pdf', { type: 'application/pdf' })
    await user.upload(input, file)
    await user.click(screen.getByRole('button', { name: 'Upload' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('No meaningful text could be extracted')
  })
})
