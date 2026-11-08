import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { api } from '../api';
import { useAuth } from '../auth';
import type { EventDetails, Order, SeatMap } from '../types';
import { ErrorBox, money, useLoad, when } from '../ui';

const MAX_SEATS = 6;

export default function EventPage() {
  const { id } = useParams();
  const { user } = useAuth();
  const navigate = useNavigate();
  const details = useLoad(() => api<EventDetails>(`/api/events/${id}`, { auth: false }), [id]);
  // the seat map is refreshed every 5 seconds, so seats held or bought by others show up
  const seats = useLoad(() => api<SeatMap>(`/api/events/${id}/seats`, { auth: false }), [id], 5000);
  const [selected, setSelected] = useState<number[]>([]);
  const [holdError, setHoldError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  // a selected seat that somebody else took meanwhile is dropped from the selection
  useEffect(() => {
    if (!seats.data) return;
    const free = new Set(
      seats.data.sections.flatMap((s) => s.rows.flatMap((r) => r.seats.filter((x) => x.status === 'AVAILABLE').map((x) => x.id))),
    );
    setSelected((current) => (current.every((s) => free.has(s)) ? current : current.filter((s) => free.has(s))));
  }, [seats.data]);

  function toggle(seatId: number) {
    setHoldError(null);
    setSelected((current) =>
      current.includes(seatId) ? current.filter((s) => s !== seatId) : current.length >= MAX_SEATS ? current : [...current, seatId],
    );
  }

  async function hold() {
    setBusy(true);
    setHoldError(null);
    try {
      const order = await api<Order>('/api/orders', { method: 'POST', body: { eventId: Number(id), seatIds: selected } });
      navigate(`/orders/${order.id}`);
    } catch (e) {
      setHoldError(e);
      setSelected([]);
      seats.reload();
    } finally {
      setBusy(false);
    }
  }

  const priceOf = new Map(seats.data?.sections.flatMap((s) => s.rows.flatMap((r) => r.seats.map((x) => [x.id, s.price] as const))));
  const total = selected.reduce((sum, seatId) => sum + (priceOf.get(seatId) ?? 0), 0);
  const d = details.data;

  return (
    <>
      <ErrorBox error={details.error} />
      {d && (
        <>
          <h1>{d.title}</h1>
          <p className="muted">{d.category} - {d.venue.name}, {d.venue.city} ({d.venue.address})</p>
          <p><b>{when(d.startsAt)}</b></p>
          {d.description && <p>{d.description}</p>}
          <table>
            <thead><tr><th>Section</th><th>Price</th><th>Available</th></tr></thead>
            <tbody>
              {d.sections.map((s) => (
                <tr key={s.sectionId}><td>{s.name}</td><td>{money(s.price)}</td><td>{s.availableSeats} / {s.totalSeats}</td></tr>
              ))}
            </tbody>
          </table>
        </>
      )}

      <h2>Choose your seats</h2>
      <div className="legend">
        <span><i style={{ background: 'var(--ok)' }} />Available</span>
        <span><i style={{ background: 'var(--primary)' }} />Selected</span>
        <span><i style={{ background: 'var(--warn)' }} />Held by someone</span>
        <span><i style={{ background: 'var(--bad)' }} />Sold</span>
      </div>
      <ErrorBox error={seats.error} />
      <div className="stage">STAGE</div>
      {seats.data?.sections.map((section) => (
        <div key={section.sectionId} className="card">
          <b>{section.name}</b> <span className="muted">{money(section.price)} per seat</span>
          {section.rows.map((row) => (
            <div key={row.label} className="seatrow">
              <span className="label">Row {row.label}</span>
              {row.seats.map((seat) => (
                <button
                  key={seat.id}
                  title={`Row ${row.label}, seat ${seat.number} - ${seat.status}`}
                  className={`seat ${selected.includes(seat.id) ? 'selected' : seat.status}`}
                  disabled={seat.status !== 'AVAILABLE' || user?.role !== 'CUSTOMER'}
                  onClick={() => toggle(seat.id)}
                >
                  {seat.number}
                </button>
              ))}
            </div>
          ))}
        </div>
      ))}

      <div className="card">
        {!user && <p>To book seats, <Link to="/login">log in</Link> as a customer.</p>}
        {user && user.role !== 'CUSTOMER' && <p className="muted">Only customers can book seats (you are {user.role}).</p>}
        {user?.role === 'CUSTOMER' && (
          <>
            <p>
              Selected: <b>{selected.length}</b> of at most {MAX_SEATS} seats - total <b>{money(total)}</b>
            </p>
            <button className="primary" disabled={selected.length === 0 || busy} onClick={() => void hold()}>
              Hold these seats for 10 minutes
            </button>
          </>
        )}
        <ErrorBox error={holdError} />
      </div>
    </>
  );
}
