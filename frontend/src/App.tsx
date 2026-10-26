import { Navigate, Route, Routes, useLocation } from 'react-router-dom';
import type { ReactNode } from 'react';
import { useAuth } from './auth';
import type { Role } from './types';
import Navbar from './components/Navbar';
import Home from './pages/Home';
import TeamPage from './pages/TeamPage';
import Login from './pages/Login';
import Register from './pages/Register';
import ApplyPage from './pages/ApplyPage';
import StudentDashboard from './pages/StudentDashboard';
import Profile from './pages/Profile';
import TeamDashboard from './pages/TeamDashboard';
import TeamEditor from './pages/TeamEditor';
import FormBuilder from './pages/FormBuilder';
import ApplicationsReview from './pages/ApplicationsReview';
import AdminPage from './pages/AdminPage';

function RequireRole({ roles, children }: { roles: Role[]; children: ReactNode }) {
  const { user, loading } = useAuth();
  const loc = useLocation();
  if (loading) return <div className="container"><p className="muted">Loading…</p></div>;
  if (!user) return <Navigate to={`/login?next=${encodeURIComponent(loc.pathname + loc.search)}`} replace />;
  if (!roles.includes(user.role)) return <div className="container"><p>You don't have access to this page.</p></div>;
  return <>{children}</>;
}

export default function App() {
  return (
    <>
      <Navbar />
      <main>
        <Routes>
          <Route path="/" element={<Home />} />
          <Route path="/teams/:id" element={<TeamPage />} />
          <Route path="/login" element={<Login />} />
          <Route path="/register" element={<Register />} />
          <Route path="/profile" element={<RequireRole roles={['STUDENT', 'TEAM', 'ADMIN']}><Profile /></RequireRole>} />

          <Route path="/apply/:teamId" element={<RequireRole roles={['STUDENT']}><ApplyPage /></RequireRole>} />
          <Route path="/my/applications" element={<RequireRole roles={['STUDENT']}><StudentDashboard /></RequireRole>} />

          <Route path="/manage" element={<RequireRole roles={['TEAM', 'ADMIN']}><TeamDashboard /></RequireRole>} />
          <Route path="/manage/new" element={<RequireRole roles={['TEAM', 'ADMIN']}><TeamEditor /></RequireRole>} />
          <Route path="/manage/:teamId/profile" element={<RequireRole roles={['TEAM', 'ADMIN']}><TeamEditor /></RequireRole>} />
          <Route path="/manage/:teamId/form" element={<RequireRole roles={['TEAM', 'ADMIN']}><FormBuilder /></RequireRole>} />
          <Route path="/manage/:teamId/applications" element={<RequireRole roles={['TEAM', 'ADMIN']}><ApplicationsReview /></RequireRole>} />

          <Route path="/admin" element={<RequireRole roles={['ADMIN']}><AdminPage /></RequireRole>} />
          <Route path="*" element={<div className="container"><h2>Page not found</h2></div>} />
        </Routes>
      </main>
      <footer className="footer container">
        ReadySetRecruit · Free for students and teams · Listings for teams that haven't joined yet link to their own websites.
      </footer>
    </>
  );
}
