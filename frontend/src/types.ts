export type Role = 'CUSTOMER' | 'ORGANIZER' | 'ADMIN';

export interface User {
  id: number;
  email: string;
  displayName: string;
  role: Role;
}

export interface AuthResponse {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  expiresIn: number;
  user: User;
}

export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export const CATEGORIES = ['CONCERT', 'THEATRE', 'CONFERENCE', 'SPORT', 'OTHER'] as const;
export type Category = (typeof CATEGORIES)[number];

export interface PublicEvent {
  id: number;
  title: string;
  category: Category;
  startsAt: string;
  venueName: string;
  city: string;
  minPrice: number | null;
  availableSeats: number;
}

export interface EventDetails {
  id: number;
  title: string;
  description: string | null;
  category: Category;
  startsAt: string;
  venue: { name: string; city: string; address: string };
  sections: { sectionId: number; name: string; price: number; availableSeats: number; totalSeats: number }[];
}

export type SeatStatus = 'AVAILABLE' | 'HELD' | 'SOLD';

export interface SeatMap {
  eventId: number;
  sections: {
    sectionId: number;
    name: string;
    price: number;
    rows: { label: string; seats: { id: number; number: number; status: SeatStatus }[] }[];
  }[];
}

export type OrderStatus = 'PENDING' | 'PAID' | 'EXPIRED' | 'CANCELLED' | 'REFUNDED';

export interface Order {
  id: number;
  eventId: number;
  status: OrderStatus;
  holdExpiresAt: string | null;
  totalAmount: number;
  items: { eventSeatId: number; section: string; row: string; seatNumber: number; price: number }[];
}

export interface Checkout {
  paymentId: number;
  orderId: number;
  status: string;
  amount: number;
  providerPaymentId: string;
  payUrl: string;
}

export interface Ticket {
  id: number;
  orderId: number;
  eventId: number;
  eventTitle: string;
  section: string;
  row: string;
  seatNumber: number;
  status: 'VALID' | 'USED' | 'CANCELLED';
  code: string;
  usedAt: string | null;
}

export interface VenueSummary {
  id: number;
  name: string;
  city: string;
  address: string;
}

export interface Venue extends VenueSummary {
  sections: { id: number; name: string; sortOrder: number; seatCount: number }[];
  totalSeats: number;
}

export type EventStatus = 'DRAFT' | 'PUBLISHED' | 'COMPLETED' | 'CANCELLED';

export interface OrganizerEvent {
  id: number;
  venueId: number;
  title: string;
  category: Category;
  startsAt: string;
  status: EventStatus;
}

export interface EventStats {
  eventId: number;
  title: string;
  status: string;
  seats: { total: number; available: number; held: number; sold: number };
  occupancyPercent: number;
  revenue: number;
  ordersByStatus: Record<string, number>;
  sections: {
    sectionId: number;
    name: string;
    seats: { total: number; available: number; held: number; sold: number };
    occupancyPercent: number;
    revenue: number;
  }[];
}

export interface OrganizerStats {
  totals: { events: number; soldSeats: number; paidOrders: number; revenue: number };
  events: {
    eventId: number;
    title: string;
    status: string;
    startsAt: string;
    totalSeats: number;
    soldSeats: number;
    heldSeats: number;
    occupancyPercent: number;
    paidOrders: number;
    revenue: number;
  }[];
}
