import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { AppLayout, ProfileWorkspace } from './app/AppLayout'
import { UploadPage } from './features/resume/UploadPage'
import { ProfilePage } from './features/profile/ProfilePage'
import { PreferencesPage } from './features/preferences/PreferencesPage'
import { ResumeRoutesPage } from './features/resumeRoutes/ResumeRoutesPage'
import { OpportunitiesPage } from './features/opportunities/OpportunitiesPage'
import { JobDetailPage } from './features/opportunities/JobDetailPage'

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route element={<AppLayout />}>
          <Route path="/" element={<UploadPage />} />
          <Route path="/profiles/:profileId" element={<ProfileWorkspace />}>
            <Route index element={<ProfilePage />} />
            <Route path="preferences" element={<PreferencesPage />} />
            <Route path="resume-routes" element={<ResumeRoutesPage />} />
            <Route path="opportunities" element={<OpportunitiesPage />} />
            <Route path="jobs/:jobId" element={<JobDetailPage />} />
          </Route>
          <Route path="*" element={<Navigate to="/" replace />} />
        </Route>
      </Routes>
    </BrowserRouter>
  )
}
