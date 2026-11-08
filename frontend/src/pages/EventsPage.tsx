import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api';
import { CATEGORIES, type Page, type PublicEvent } from '../types';
import { ErrorBox, money, useLoad, when } from '../ui';

interface Filters {
  q: string;
  city: string;
  category: string;
  from: string;
  to: string;
  maxPrice: string;
  sort: string;
}

const EMPTY: Filters = { q: '', city: '', category: '', from: '', to: '', maxPrice: '', sort: 'startsAt' };

function toQuery(f: Filters, page: number): string {
  const p = new URLSearchParams();
  if (f.q) p.set('q', f.q);
  if (f.city) p.set('city', f.city);
  if (f.category) p.set('category', f.category);
  if (f.from) p.set('from', new Date(f.from).toISOString());
  if (f.to) p.set('to', new Date(f.to).toISOString());
  if (f.maxPrice) p.set('maxPrice', f.maxPrice);
  p.set('sort', f.sort);
  p.set('page', String(page));
  p.set('size', '12');
  return p.toString();
}

export default function EventsPage() {
  const [form, setForm] = useState<Filters>(EMPTY);
  const [applied, setApplied] = useState<Filters>(EMPTY);
  const [page, setPage] = useState(0);
  const { data, error } = useLoad(
    () => api<Page<PublicEvent>>(`/api/events?${toQuery(applied, page)}`, { auth: false }),
    [applied, page],
  );

  const set = (key: keyof Filters) => (e: { target: { value: string } }) => setForm({ ...form, [key]: e.target.value });

  function submit(e: FormEvent) {
    e.preventDefault();
    setPage(0);
    setApplied(form);
  }

  return (
    <>
      <h1>Events</h1>
      <form className="card" onSubmit={submit}>
        <div className="row">
          <label>Search<input value={form.q} onChange={set('q')} placeholder="title..." /></label>
          <label>City<input value={form.city} onChange={set('city')} /></label>
          <label>Category
            <select value={form.category} onChange={set('category')}>
              <option value="">All</option>
              {CATEGORIES.map((c) => <option key={c}>{c}</option>)}
            </select>
          </label>
          <label>From<input type="date" value={form.from} onChange={set('from')} /></label>
          <label>To<input type="date" value={form.to} onChange={set('to')} /></label>
          <label>Max price<input type="number" min="0" value={form.maxPrice} onChange={set('maxPrice')} style={{ width: 90 }} /></label>
          <label>Sort
            <select value={form.sort} onChange={set('sort')}>
              <option value="startsAt">Date (soonest)</option>
              <option value="startsAt,desc">Date (latest)</option>
              <option value="title">Title</option>
              <option value="minPrice">Price (lowest)</option>
              <option value="minPrice,desc">Price (highest)</option>
            </select>
          </label>
          <button className="primary">Search</button>
          <button type="button" onClick={() => { setForm(EMPTY); setApplied(EMPTY); setPage(0); }}>Clear</button>
        </div>
      </form>
      <ErrorBox error={error} />
      {data && data.content.length === 0 && <p>No events match.</p>}
      <div className="grid">
        {data?.content.map((e) => (
          <Link key={e.id} to={`/events/${e.id}`} className="card" style={{ color: 'inherit' }}>
            <b>{e.title}</b>
            <div className="muted">{e.category} - {e.venueName}, {e.city}</div>
            <div>{when(e.startsAt)}</div>
            <div>from {money(e.minPrice)}</div>
            <div className={e.availableSeats === 0 ? 'muted' : ''}>{e.availableSeats} seats left</div>
          </Link>
        ))}
      </div>
      {data && data.totalPages > 1 && (
        <div className="row">
          <button disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button>
          <span>Page {data.page + 1} of {data.totalPages} ({data.totalElements} events)</span>
          <button disabled={page + 1 >= data.totalPages} onClick={() => setPage(page + 1)}>Next</button>
        </div>
      )}
    </>
  );
}
