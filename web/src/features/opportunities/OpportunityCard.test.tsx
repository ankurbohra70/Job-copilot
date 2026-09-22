import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import { OpportunityCard } from './OpportunityCard'
import { sampleOpportunity } from '../../test/fixtures'

describe('OpportunityCard', () => {
  it('renders backend score, decision, and readiness without deriving them', () => {
    render(
      <MemoryRouter>
        <OpportunityCard profileId={1} opportunity={sampleOpportunity()} />
      </MemoryRouter>,
    )
    expect(screen.getByText('81')).toBeInTheDocument()
    expect(screen.getByText('GOOD_MATCH')).toBeInTheDocument()
    expect(screen.getByText('APPLY_VOLUME')).toBeInTheDocument()
    expect(screen.getByText('READY')).toBeInTheDocument()
  })
})
