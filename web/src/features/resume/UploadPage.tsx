import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '../../api/client'
import { isApiError } from '../../api/errors'
import { rememberProfileId } from '../../app/session'
import { Banner } from '../../shared/ui/Feedback'

const HINT_BYTES = 5 * 1024 * 1024

export function UploadPage() {
  const navigate = useNavigate()
  const [file, setFile] = useState<File | null>(null)
  const [hint, setHint] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [pending, setPending] = useState(false)

  function onFile(next: File | null) {
    setFile(next)
    setError(null)
    if (!next) {
      setHint(null)
      return
    }
    const messages: string[] = []
    if (!next.name.toLowerCase().endsWith('.pdf')) messages.push('Choose a PDF file.')
    if (next.size > HINT_BYTES) messages.push('This file looks larger than 5MB.')
    setHint(messages.length ? messages.join(' ') : null)
  }

  async function onSubmit(event: FormEvent) {
    event.preventDefault()
    if (!file || pending) return
    setPending(true)
    setError(null)
    try {
      const resume = await api.uploadResume(file)
      const profileId = resume.candidateProfile.id
      rememberProfileId(profileId)
      setFile(null)
      navigate(`/profiles/${profileId}`)
    } catch (caught) {
      setError(isApiError(caught) ? caught.userMessage : 'Something went wrong. Please try again.')
    } finally {
      setPending(false)
    }
  }

  return (
    <section className="stack">
      <h1>Upload resume</h1>
      <p>Upload a text-based PDF. Extraction creates a draft profile you must review and confirm.</p>
      {error ? <Banner kind="error">{error}</Banner> : null}
      {hint ? <Banner kind="warn">{hint} The server still decides whether the file is accepted.</Banner> : null}
      <form className="card stack" onSubmit={onSubmit}>
        <label htmlFor="resume-file">Resume PDF</label>
        <input
          id="resume-file"
          name="file"
          type="file"
          accept="application/pdf,.pdf"
          disabled={pending}
          onChange={(event) => onFile(event.target.files?.[0] ?? null)}
        />
        <button type="submit" disabled={pending || !file}>
          {pending ? 'Uploading…' : 'Upload'}
        </button>
      </form>
    </section>
  )
}
