export interface UserDto {
  id: string;
  name: string;
  age: number;
  avatarUrl?: string | null;
  formal: boolean;
}

export type Intent = 'TRACKING' | 'CANCEL' | 'RETURN' | 'SUPPORT' | 'TICKETS' | 'FAQ' | 'CHATTY' | 'OTHER' | 'NONE';

export interface ConfirmAction {
  orderId: number;
  title: string;
  warning: string;
  confirmLabel: string;
  confirmPayload: string;
  cancelLabel: string;
  cancelPayload: string;
}

export interface OrderCard {
  id: number;
  description: string;
  status: string;
  imageUrl?: string;
}

export interface TicketCard {
  id: number;
  code: string;
  title: string;
  status: string;
  createdAt: string;
}

export interface TicketDetail {
  id: number;
  code: string;
  title: string;
  message: string;
  status: string;
  orderId?: number | null;
  createdAt: string;
}

export interface ReturnCard {
  id: number;
  code: string;
  description: string;
  status: string;
  createdAt: string;
}

export interface ReturnLabel {
  id: number;
  code: string;
  orderDescription: string;
  status: string;
  carrier: string;
  destinationName: string;
  destinationAddress: string;
  barcode: string;
  createdAt: string;
}

export interface ChatRequest {
  conversationId: string;
  message: string;
}

export interface ChatResponse {
  conversationId: string;
  reply: string;
  intent: Intent;
  resolved: boolean;
  orders?: OrderCard[];
  // Decided by the backend (awaiting ORDER_CHOICE): a clickable list vs the detail of the focused order.
  ordersSelectable?: boolean;
  tickets?: TicketCard[];
  returns?: ReturnCard[];
  confirm?: ConfirmAction | null;
}

export interface ChatMessage {
  from: 'user' | 'agent';
  text: string;
  intent?: Intent;
  at: Date;
  orders?: OrderCard[];
  ordersSelectable?: boolean;
  tickets?: TicketCard[];
  returns?: ReturnCard[];
  confirm?: ConfirmAction | null;
}
