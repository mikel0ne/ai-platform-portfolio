export interface UserDto {
  id: string;
  name: string;
  age: number;
  avatarUrl?: string | null;
  /** true si al usuario se le trata de usted (lo decide el backend según la edad). */
  formal: boolean;
}

export type Intent = 'TRACKING' | 'CANCEL' | 'SUPPORT' | 'FAQ' | 'CHATTY' | 'OTHER' | 'NONE';

/** Acción de confirmación (botones) para una operación irreversible, p. ej. cancelar un pedido. */
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
  tickets?: TicketCard[];
  confirm?: ConfirmAction | null;
}

export interface ChatMessage {
  from: 'user' | 'agent';
  text: string;
  intent?: Intent;
  at: Date;
  orders?: OrderCard[];
  tickets?: TicketCard[];
  confirm?: ConfirmAction | null;
}
