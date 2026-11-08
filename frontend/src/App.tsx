import { Link, NavLink, Route, Routes } from 'react-router-dom';
import { useAuth } from './auth';
import { RequireRole } from './ui';
import AccountPage from './pages/AccountPage';
import AdminPage from './pages/AdminPage';
import EventPage from './pages/EventPage';
import EventsPage from './pages/EventsPage';
import LoginPage from './pages/LoginPage';
import OrderPage from './pages/OrderPage';
import OrdersPage from './pages/OrdersPage';
import OrganizerPage from './pages/OrganizerPage';
import TicketsPage from './pages/TicketsPage';

export default function App() {
  const { user, logout } = useAuth();
  const isOrganizer = user?.role === 'ORGANIZER' || user?.role === 'ADMIN';
  return (
    <>
      <header className="top">
        <Link to="/" className="brand">TicketFlow</Link>
        <nav>
          <NavLink to="/">Events</NavLink>
          {user?.role === 'CUSTOMER' && <NavLink to="/orders">My orders</NavLink>}
          {user?.role === 'CUSTOMER' && <NavLink to="/tickets">My tickets</NavLink>}
          {isOrganizer && <NavLink to="/organizer">Organizer</NavLink>}
          {user?.role === 'ADMIN' && <NavLink to="/admin">Admin</NavLink>}
        </nav>
        <div className="who">
          {user ? (
            <>
              <Link to="/account">{user.displayName} <span className="role">{user.role}</span></Link>
              <button onClick={() => void logout()}>Log out</button>
            </>
          ) : (
            <Link to="/login">Log in / Register</Link>
          )}
        </div>
      </header>
      <main>
        <Routes>
          <Route path="/" element={<EventsPage />} />
          <Route path="/events/:id" element={<EventPage />} />
          <Route path="/login" element={<LoginPage />} />
          <Route path="/account" element={<RequireRole><AccountPage /></RequireRole>} />
          <Route path="/orders" element={<RequireRole roles={['CUSTOMER']}><OrdersPage /></RequireRole>} />
          <Route path="/orders/:id" element={<RequireRole roles={['CUSTOMER']}><OrderPage /></RequireRole>} />
          <Route path="/tickets" element={<RequireRole roles={['CUSTOMER']}><TicketsPage /></RequireRole>} />
          <Route path="/organizer" element={<RequireRole roles={['ORGANIZER', 'ADMIN']}><OrganizerPage /></RequireRole>} />
          <Route path="/admin" element={<RequireRole roles={['ADMIN']}><AdminPage /></RequireRole>} />
          <Route path="*" element={<p>Page not found.</p>} />
        </Routes>
      </main>
    </>
  );
}
