export interface UserDto {
  id: string;
  name: string;
  age: number;
  avatarUrl?: string | null;
  /** true si al usuario se le trata de usted (lo decide el backend según la edad). */
  formal: boolean;
}

export type Intent = 'TRACKING' | 'CANCEL' | 'RETURN' | 'SUPPORT' | 'FAQ' | 'CHATTY' | 'OTHER' | 'NONE';

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

/** Tarjeta de devolución: enlace clicable a la etiqueta de devolución (/returns/{id}). */
export interface ReturnCard {
  id: number;
  code: string;
  description: string;
  status: string;
  createdAt: string;
}

/** Etiqueta de devolución para la página /returns/{id}. */
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
  tickets?: TicketCard[];
  returns?: ReturnCard[];
  confirm?: ConfirmAction | null;
}
