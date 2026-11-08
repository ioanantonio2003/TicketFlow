import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api';
import {
  CATEGORIES,
  type Category,
  type EventStats,
  type OrganizerEvent,
  type OrganizerStats,
  type Page,
  type Venue,
  type VenueSummary,
} from '../types';
import { Badge, ErrorBox, InfoBox, money, useLoad, when } from '../ui';

type Tab = 'overview' | 'venues' | 'events' | 'scan';

export default function OrganizerPage() {
  const [tab, setTab] = useState<Tab>('overview');
  const tabs: [Tab, string][] = [['overview', 'Sales overview'], ['venues', 'Venues'], ['events', 'Events'], ['scan', 'Scan tickets']];
  return (
    <>
      <h1>Organizer</h1>
      <div className="row">
        {tabs.map(([key, label]) => (
          <button key={key} className={tab === key ? 'primary' : ''} onClick={() => setTab(key)}>{label}</button>
        ))}
      </div>
      {tab === 'overview' && <Overview />}
      {tab === 'venues' && <Venues />}
      {tab === 'events' && <Events />}
      {tab === 'scan' && <Scan />}
    </>
  );
}

// ---------------------------------------------------------------- sales overview

function Overview() {
  const { data, error, reload } = useLoad(() => api<OrganizerStats>('/api/organizer/stats'));
  return (
    <>
      <button onClick={reload}>Refresh</button>
      <ErrorBox error={error} />
      {data && (
        <>
          <div className="card">
            <b>{data.totals.events}</b> events - <b>{data.totals.soldSeats}</b> seats sold - <b>{data.totals.paidOrders}</b> paid orders - revenue <b>{money(data.totals.revenue)}</b>
          </div>
          <table>
            <thead><tr><th>Event</th><th>Status</th><th>Starts</th><th>Sold / total</th><th>Held</th><th>Occupancy</th><th>Paid orders</th><th>Revenue</th></tr></thead>
            <tbody>
              {data.events.map((e) => (
                <tr key={e.eventId}>
                  <td>{e.title}</td><td><Badge value={e.status} /></td><td>{when(e.startsAt)}</td>
                  <td>{e.soldSeats} / {e.totalSeats}</td><td>{e.heldSeats}</td><td>{e.occupancyPercent}%</td>
                  <td>{e.paidOrders}</td><td>{money(e.revenue)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
    </>
  );
}

// ---------------------------------------------------------------- venues

function Venues() {
  const { data, error, reload } = useLoad(() => api<Page<VenueSummary>>('/api/venues?size=50'));
  const [name, setName] = useState('');
  const [city, setCity] = useState('');
  const [address, setAddress] = useState('');
  // each section: a name and its rows as "label:seats" pairs, e.g. "A:10, B:10"
  const [sections, setSections] = useState([{ name: 'Floor', rows: 'A:10, B:10' }]);
  const [formError, setFormError] = useState<unknown>(null);
  const [message, setMessage] = useState('');

  function parseRows(text: string) {
    return text.split(',').map((part) => {
      const [label, seats] = part.split(':').map((s) => s.trim());
      return { label, seats: Number(seats) };
    });
  }

  async function create(e: FormEvent) {
    e.preventDefault();
    setFormError(null);
    setMessage('');
    try {
      const venue = await api<Venue>('/api/venues', {
        method: 'POST',
        body: { name, city, address, sections: sections.map((s) => ({ name: s.name, rows: parseRows(s.rows) })) },
      });
      setMessage(`Venue "${venue.name}" created with ${venue.totalSeats} seats.`);
      reload();
    } catch (err) {
      setFormError(err);
    }
  }

  async function remove(id: number) {
    setFormError(null);
    setMessage('');
    try {
      await api(`/api/venues/${id}`, { method: 'DELETE' });
      setMessage('Venue deleted.');
      reload();
    } catch (err) {
      setFormError(err);
    }
  }

  return (
    <>
      <form className="card" onSubmit={create}>
        <h3>New venue</h3>
        <div className="row">
          <label>Name<input value={name} onChange={(e) => setName(e.target.value)} required /></label>
          <label>City<input value={city} onChange={(e) => setCity(e.target.value)} required /></label>
          <label>Address<input value={address} onChange={(e) => setAddress(e.target.value)} required /></label>
        </div>
        {sections.map((s, i) => (
          <div className="row" key={i}>
            <label>Section name
              <input value={s.name} onChange={(e) => setSections(sections.map((x, j) => (j === i ? { ...x, name: e.target.value } : x)))} required />
            </label>
            <label>Rows as label:seats, comma separated
              <input style={{ width: 260 }} value={s.rows} onChange={(e) => setSections(sections.map((x, j) => (j === i ? { ...x, rows: e.target.value } : x)))} required />
            </label>
            {sections.length > 1 && <button type="button" onClick={() => setSections(sections.filter((_, j) => j !== i))}>Remove</button>}
          </div>
        ))}
        <div className="row">
          <button type="button" onClick={() => setSections([...sections, { name: '', rows: 'A:10' }])}>Add section</button>
          <button className="primary">Create venue</button>
        </div>
        <ErrorBox error={formError} />
        <InfoBox>{message}</InfoBox>
      </form>
      <ErrorBox error={error} />
      <table>
        <thead><tr><th>Id</th><th>Name</th><th>City</th><th>Address</th><th /></tr></thead>
        <tbody>
          {data?.content.map((v) => (
            <tr key={v.id}>
              <td>{v.id}</td><td>{v.name}</td><td>{v.city}</td><td>{v.address}</td>
              <td><button className="danger" onClick={() => void remove(v.id)}>Delete</button></td>
            </tr>
          ))}
        </tbody>
      </table>
      <p className="muted">A venue that has events cannot be deleted (the server refuses with 409).</p>
    </>
  );
}

// ---------------------------------------------------------------- events

function Events() {
  const events = useLoad(() => api<Page<OrganizerEvent>>('/api/organizer/events?size=50&sort=startsAt,desc'));
  const venues = useLoad(() => api<Page<VenueSummary>>('/api/venues?size=100'));
  const [venueId, setVenueId] = useState('');
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [category, setCategory] = useState<Category>('CONCERT');
  const [startsAt, setStartsAt] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [message, setMessage] = useState('');
  const [open, setOpen] = useState<{ id: number; what: 'prices' | 'stats' } | null>(null);

  async function create(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setMessage('');
    try {
      await api('/api/events', {
        method: 'POST',
        body: { venueId: Number(venueId), title, description, category, startsAt: new Date(startsAt).toISOString() },
      });
      setMessage('Event created as DRAFT. Set the prices, then publish it.');
      events.reload();
    } catch (err) {
      setError(err);
    }
  }

  async function action(id: number, path: 'publish' | 'cancel', text: string) {
    setError(null);
    setMessage('');
    try {
      await api(`/api/events/${id}/${path}`, { method: 'POST' });
      setMessage(text);
      events.reload();
    } catch (err) {
      setError(err);
    }
  }

  return (
    <>
      <form className="card" onSubmit={create}>
        <h3>New event</h3>
        <div className="row">
          <label>Venue
            <select value={venueId} onChange={(e) => setVenueId(e.target.value)} required>
              <option value="">Choose...</option>
              {venues.data?.content.map((v) => <option key={v.id} value={v.id}>{v.name} ({v.city})</option>)}
            </select>
          </label>
          <label>Title<input value={title} onChange={(e) => setTitle(e.target.value)} required /></label>
          <label>Category
            <select value={category} onChange={(e) => setCategory(e.target.value as Category)}>
              {CATEGORIES.map((c) => <option key={c}>{c}</option>)}
            </select>
          </label>
          <label>Starts at<input type="datetime-local" value={startsAt} onChange={(e) => setStartsAt(e.target.value)} required /></label>
        </div>
        <div className="row">
          <label style={{ flex: 1 }}>Description<input value={description} onChange={(e) => setDescription(e.target.value)} /></label>
          <button className="primary">Create event</button>
        </div>
      </form>
      <ErrorBox error={error} />
      <InfoBox>{message}</InfoBox>
      <ErrorBox error={events.error} />
      {events.data?.content.map((ev) => (
        <div className="card" key={ev.id}>
          <b>{ev.title}</b> <Badge value={ev.status} /> <span className="muted">#{ev.id} - {ev.category} - {when(ev.startsAt)}</span>
          <div className="row">
            {ev.status === 'DRAFT' && <button onClick={() => setOpen({ id: ev.id, what: 'prices' })}>Set prices</button>}
            {ev.status === 'DRAFT' && <button className="primary" onClick={() => void action(ev.id, 'publish', 'Published: the seats now exist and can be booked.')}>Publish</button>}
            {(ev.status === 'DRAFT' || ev.status === 'PUBLISHED') && (
              <button className="danger" onClick={() => void action(ev.id, 'cancel', 'Event cancelled: pending orders cancelled, paid orders refunded, customers emailed.')}>Cancel event</button>
            )}
            <button onClick={() => setOpen({ id: ev.id, what: 'stats' })}>Stats</button>
            {ev.status === 'PUBLISHED' && <Link to={`/events/${ev.id}`}>Public page</Link>}
          </div>
          {open?.id === ev.id && open.what === 'prices' && <Prices event={ev} onDone={() => { setOpen(null); setMessage('Prices saved.'); }} />}
          {open?.id === ev.id && open.what === 'stats' && <Stats eventId={ev.id} />}
        </div>
      ))}
    </>
  );
}

function Prices({ event, onDone }: { event: OrganizerEvent; onDone: () => void }) {
  const venue = useLoad(() => api<Venue>(`/api/venues/${event.venueId}`), [event.venueId]);
  const [prices, setPrices] = useState<Record<number, string>>({});
  const [error, setError] = useState<unknown>(null);

  async function save(e: FormEvent) {
    e.preventDefault();
    setError(null);
    try {
      const body = { prices: (venue.data?.sections ?? []).map((s) => ({ sectionId: s.id, price: Number(prices[s.id]) })) };
      await api(`/api/events/${event.id}/prices`, { method: 'PUT', body });
      onDone();
    } catch (err) {
      setError(err);
    }
  }

  return (
    <form onSubmit={save}>
      <ErrorBox error={venue.error} />
      <div className="row">
        {venue.data?.sections.map((s) => (
          <label key={s.id}>{s.name} ({s.seatCount} seats), RON
            <input type="number" min="0.01" step="0.01" value={prices[s.id] ?? ''} onChange={(e) => setPrices({ ...prices, [s.id]: e.target.value })} required />
          </label>
        ))}
        <button className="primary">Save prices</button>
      </div>
      <ErrorBox error={error} />
    </form>
  );
}

function Stats({ eventId }: { eventId: number }) {
  const { data, error, reload } = useLoad(() => api<EventStats>(`/api/organizer/events/${eventId}/stats`), [eventId]);
  return (
    <div>
      <ErrorBox error={error} />
      <button onClick={reload}>Refresh</button>
      {data && (
        <>
          <p>
            Seats: total {data.seats.total}, available {data.seats.available}, held {data.seats.held}, sold {data.seats.sold} -
            occupancy <b>{data.occupancyPercent}%</b> - revenue <b>{money(data.revenue)}</b>
          </p>
          <p>Orders: {Object.entries(data.ordersByStatus).map(([k, v]) => `${k} ${v}`).join(' | ')}</p>
          <table>
            <thead><tr><th>Section</th><th>Total</th><th>Available</th><th>Held</th><th>Sold</th><th>Occupancy</th><th>Revenue</th></tr></thead>
            <tbody>
              {data.sections.map((s) => (
                <tr key={s.sectionId}><td>{s.name}</td><td>{s.seats.total}</td><td>{s.seats.available}</td><td>{s.seats.held}</td><td>{s.seats.sold}</td><td>{s.occupancyPercent}%</td><td>{money(s.revenue)}</td></tr>
              ))}
            </tbody>
          </table>
        </>
      )}
    </div>
  );
}

// ---------------------------------------------------------------- scanning

interface Scanned {
  ticketId: number;
  eventTitle: string;
  section: string;
  row: string;
  seatNumber: number;
  usedAt: string;
}

function Scan() {
  const [code, setCode] = useState('');
  const [result, setResult] = useState<Scanned | null>(null);
  const [error, setError] = useState<unknown>(null);

  async function scan(e: FormEvent) {
    e.preventDefault();
    setResult(null);
    setError(null);
    try {
      setResult(await api<Scanned>('/api/tickets/validate', { method: 'POST', body: { code: code.trim() } }));
    } catch (err) {
      setError(err);
    }
  }

  return (
    <form className="card" onSubmit={scan}>
      <h3>Entrance scanner</h3>
      <p className="muted">Paste the code under a ticket's QR (a phone scanner would send the same text). Only tickets of your own events are accepted; a ticket works once.</p>
      <div className="row">
        <label style={{ flex: 1 }}>Ticket code<input value={code} onChange={(e) => setCode(e.target.value)} required /></label>
        <button className="primary">Validate</button>
      </div>
      <ErrorBox error={error} />
      {result && (
        <div className="box ok">
          ACCEPTED - {result.eventTitle}: {result.section}, row {result.row}, seat {result.seatNumber} (scanned {when(result.usedAt)})
        </div>
      )}
    </form>
  );
}
