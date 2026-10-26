import { Link, NavLink, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth';

export default function Navbar() {
  const { user, logout } = useAuth();
  const nav = useNavigate();
  return (
    <header className="navbar">
      <div className="container navbar-inner">
        <Link to="/" className="brand">
          <img src="/logo.png" alt="" width={36} height={36} />
          <span>Ready<span className="brand-accent">Set</span>Recruit</span>
        </Link>
        <nav className="nav-links">
          <NavLink to="/" end>Find teams</NavLink>
          {user?.role === 'STUDENT' && <NavLink to="/my/applications">My applications</NavLink>}
          {(user?.role === 'TEAM' || user?.role === 'ADMIN') && <NavLink to="/manage">My teams</NavLink>}
          {user?.role === 'ADMIN' && <NavLink to="/admin">Admin</NavLink>}
          {user ? (
            <>
              <NavLink to="/profile">{user.name.split(' ')[0]}</NavLink>
              <button className="btn btn-ghost" onClick={() => { logout(); nav('/'); }}>Log out</button>
            </>
          ) : (
            <>
              <NavLink to="/login">Log in</NavLink>
              <Link to="/register" className="btn btn-primary">Sign up</Link>
            </>
          )}
        </nav>
      </div>
    </header>
  );
}
