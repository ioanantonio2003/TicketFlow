import { Link } from 'react-router-dom';
import { api } from '../api';
import type { Order, Page } from '../types';
import { Badge, ErrorBox, money, useLoad, when } from '../ui';

export default function OrdersPage() {
  const { data, error, reload } = useLoad(() => api<Page<Order>>('/api/orders?size=50'));
  return (
    <>
      <h1>My orders</h1>
      <button onClick={reload}>Refresh</button>
      <ErrorBox error={error} />
      {data && data.content.length === 0 && <p>You have no orders yet. Pick seats on an event page.</p>}
      {data && data.content.length > 0 && (
        <table>
          <thead><tr><th>Order</th><th>Event</th><th>Status</th><th>Seats</th><th>Total</th><th>Hold until</th></tr></thead>
          <tbody>
            {data.content.map((o) => (
              <tr key={o.id}>
                <td><Link to={`/orders/${o.id}`}>#{o.id}</Link></td>
                <td><Link to={`/events/${o.eventId}`}>event {o.eventId}</Link></td>
                <td><Badge value={o.status} /></td>
                <td>{o.items.map((i) => `${i.section} ${i.row}${i.seatNumber}`).join(', ')}</td>
                <td>{money(o.totalAmount)}</td>
                <td>{o.status === 'PENDING' ? when(o.holdExpiresAt) : '-'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </>
  );
}
