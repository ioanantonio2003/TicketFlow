import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api } from '../api';
import type { Checkout, Order } from '../types';
import { Badge, ErrorBox, InfoBox, money, useLoad, useNow, when } from '../ui';

/** Works on http pages too (crypto.randomUUID needs a secure context). */
function newKey(): string {
  return typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID()
    : 'key-' + Math.random().toString(36).slice(2) + Date.now().toString(36);
}

export default function OrderPage() {
  const { id } = useParams();
  // polled every 3 s: the expiry job, the payment webhook and a refund change the order in the background
  const { data: order, error, reload } = useLoad(() => api<Order>(`/api/orders/${id}`), [id], 3000);
  const now = useNow();
  const [idempotencyKey, setIdempotencyKey] = useState(newKey);
  const [checkout, setCheckout] = useState<Checkout | null>(null);
  const [lastCheckoutBody, setLastCheckoutBody] = useState('');
  const [actionError, setActionError] = useState<unknown>(null);
  const [message, setMessage] = useState('');
  const [busy, setBusy] = useState(false);

  async function run(action: () => Promise<void>) {
    setBusy(true);
    setActionError(null);
    setMessage('');
    try {
      await action();
    } catch (e) {
      setActionError(e);
    } finally {
      setBusy(false);
      reload();
    }
  }

  const startPayment = () =>
    run(async () => {
      const result = await api<Checkout>(`/api/orders/${id}/checkout`, {
        method: 'POST',
        headers: { 'Idempotency-Key': idempotencyKey },
      });
      const body = JSON.stringify(result);
      setMessage(
        lastCheckoutBody
          ? body === lastCheckoutBody
            ? 'Same Idempotency-Key: the server returned the very same payment (no second payment was created).'
            : 'Different answer received.'
          : `Payment ${result.paymentId} created at the provider (${result.providerPaymentId}).`,
      );
      setLastCheckoutBody(body);
      setCheckout(result);
    });

  const complete = (outcome: 'success' | 'failure') =>
    run(async () => {
      if (!checkout) return;
      await api(`/fakepay/payments/${checkout.providerPaymentId}/complete?outcome=${outcome}`, { method: 'POST', auth: false });
      setMessage(`The provider was told the payment ${outcome === 'success' ? 'succeeded' : 'failed'}. It notifies the backend by webhook; this page refreshes by itself.`);
      if (outcome === 'failure') {
        setCheckout(null); // a failed payment cannot be completed again: the customer starts a new attempt
        setLastCheckoutBody('');
        setIdempotencyKey(newKey());
      }
    });

  const cancel = () => run(async () => { await api(`/api/orders/${id}/cancel`, { method: 'POST' }); setMessage('Order cancelled.'); });
  const refund = () => run(async () => {
    const r = await api<{ amount: number }>(`/api/orders/${id}/refund`, { method: 'POST' });
    setMessage(`Refunded ${money(r.amount)}. The tickets are cancelled and the seats are free again.`);
  });

  const secondsLeft = order?.holdExpiresAt ? Math.max(0, Math.floor((new Date(order.holdExpiresAt).getTime() - now) / 1000)) : 0;

  return (
    <>
      <p><Link to="/orders">&larr; My orders</Link></p>
      <ErrorBox error={error} />
      {order && (
        <>
          <h1>Order #{order.id} <Badge value={order.status} /></h1>
          <p>Event: <Link to={`/events/${order.eventId}`}>#{order.eventId}</Link> - Total: <b>{money(order.totalAmount)}</b></p>
          <table>
            <thead><tr><th>Seat</th><th>Price</th></tr></thead>
            <tbody>
              {order.items.map((i) => (
                <tr key={i.eventSeatId}><td>{i.section}, row {i.row}, seat {i.seatNumber}</td><td>{money(i.price)}</td></tr>
              ))}
            </tbody>
          </table>

          {order.status === 'PENDING' && (
            <div className="card">
              <div className={`timer ${secondsLeft < 60 ? 'low' : ''}`}>
                Seats held for {Math.floor(secondsLeft / 60)}:{String(secondsLeft % 60).padStart(2, '0')}
              </div>
              <p className="muted">Hold ends {when(order.holdExpiresAt)}. After that the seats are released automatically (the job runs every 30 s).</p>

              <h3>1. Start the payment</h3>
              <p className="muted">Idempotency-Key: <code>{idempotencyKey}</code></p>
              <div className="row">
                <button className="primary" disabled={busy || secondsLeft === 0} onClick={() => void startPayment()}>
                  {checkout ? 'Send checkout again (same key)' : 'Checkout'}
                </button>
                <button disabled={busy} onClick={() => { setIdempotencyKey(newKey()); setLastCheckoutBody(''); setCheckout(null); }}>
                  New key (new attempt)
                </button>
                <button className="danger" disabled={busy} onClick={() => void cancel()}>Cancel order</button>
              </div>

              {checkout && (
                <>
                  <h3>2. Pay at the (simulated) provider</h3>
                  <p className="muted">Provider payment <code>{checkout.providerPaymentId}</code> for {money(checkout.amount)}. In real life the customer is redirected to the provider's page; here you play that page.</p>
                  <div className="row">
                    <button className="primary" disabled={busy} onClick={() => void complete('success')}>Pay successfully</button>
                    <button className="danger" disabled={busy} onClick={() => void complete('failure')}>Payment fails</button>
                  </div>
                </>
              )}
            </div>
          )}

          {order.status === 'PAID' && (
            <div className="card">
              <p>Paid. Your tickets are on <Link to="/tickets">My tickets</Link> (and in the email, see Mailpit on port 8025).</p>
              <button className="danger" disabled={busy} onClick={() => void refund()}>Refund this order</button>
              <p className="muted">Refunds are possible until 24 hours before the event starts.</p>
            </div>
          )}
          {order.status === 'EXPIRED' && <InfoBox>The hold ran out and the seats were released. Pick seats again.</InfoBox>}
          {order.status === 'CANCELLED' && <InfoBox>This order was cancelled; the seats were released.</InfoBox>}
          {order.status === 'REFUNDED' && <InfoBox>This order was refunded.</InfoBox>}

          <InfoBox>{message}</InfoBox>
          <ErrorBox error={actionError} />
        </>
      )}
    </>
  );
}
