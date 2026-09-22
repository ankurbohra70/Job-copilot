import { cloneElement, isValidElement, type ReactElement, type ReactNode } from 'react'

export function Banner({
  kind,
  children,
}: {
  kind: 'error' | 'warn' | 'ok' | 'info'
  children: ReactNode
}) {
  return (
    <div className={`banner ${kind}`} role={kind === 'error' ? 'alert' : 'status'}>
      {children}
    </div>
  )
}

export function Loading({ label = 'Loading' }: { label?: string }) {
  return <p className="muted">{label}…</p>
}

export function Empty({ children }: { children: ReactNode }) {
  return <p className="muted">{children}</p>
}

export function Field({
  id,
  label,
  error,
  children,
}: {
  id: string
  label: string
  error?: string
  children: ReactNode
}) {
  const errorId = `${id}-error`
  const control = isValidElement(children)
    ? cloneElement(children as ReactElement<{ id?: string; 'aria-describedby'?: string; 'aria-invalid'?: boolean | 'true' | 'false' }>, {
        id,
        'aria-describedby': error ? errorId : undefined,
        'aria-invalid': error ? true : undefined,
      })
    : children
  return (
    <label htmlFor={id}>
      {label}
      {control}
      {error ? (
        <span id={errorId} className="field-error">
          {error}
        </span>
      ) : null}
    </label>
  )
}
