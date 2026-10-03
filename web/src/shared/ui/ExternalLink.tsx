import { isSafeHttpUrl } from '../../api/urls'

export function ExternalLink({
  href,
  children,
}: {
  href: string
  children: string
}) {
  if (!isSafeHttpUrl(href)) {
    return <span className="muted">{children} (link unavailable)</span>
  }
  return (
    <a href={href} target="_blank" rel="noopener noreferrer">
      {children}
    </a>
  )
}
