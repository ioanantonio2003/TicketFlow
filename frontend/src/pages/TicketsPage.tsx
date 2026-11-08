import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api, apiBlobUrl } from '../api';
import type { Page, Ticket } from '../types';
import { Badge, ErrorBox, useLoad, when } from '../ui';

function Qr({ ticketId }: { ticketId: number }) {
  const [url, setUrl] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    let objectUrl: string | null = null;
    apiBlobUrl(`/api/tickets/${ticketId}/qr`).then(
      (u) => { objectUrl = u; setUrl(u); },
      () => setFailed(true),
    );
    return () => { if (objectUrl) URL.revokeObjectURL(objectUrl); };
  }, [ticketId]);
  if (failed) return <span className="muted">QR unavailable</span>;
  return url ? <img className="qr" src={url} alt={`QR code of ticket ${ticketId}`} /> : <span className="muted">...</span>;
}

export default function TicketsPage() {
  const { data, error, reload } = useLoad(() => api<Page<Ticket>>('/api/tickets?size=50'));
  return (
    <>
      <h1>My tickets</h1>
      <p className="muted">
        Show the QR code at the entrance. The organizer scans it once: the second scan of the same ticket is refused.
        Tickets of refunded orders are cancelled.
      </p>
      <button onClick={reload}>Refresh (see if a ticket was used)</button>
      <ErrorBox error={error} />
      {data && data.content.length === 0 && <p>No tickets yet. Tickets appear a moment after a payment succeeds. <Link to="/orders">My orders</Link></p>}
      <div className="grid">
        {data?.content.map((t) => (
          <div key={t.id} className="card">
            <b>{t.eventTitle}</b> <Badge value={t.status} />
            <div>{t.section}, row {t.row}, seat {t.seatNumber}</div>
            <div className="muted">order #{t.orderId}{t.usedAt ? ` - used ${when(t.usedAt)}` : ''}</div>
            <Qr ticketId={t.id} />
            <div className="muted">code: <code>{t.code}</code></div>
          </div>
        ))}
      </div>
    </>
  );
}
