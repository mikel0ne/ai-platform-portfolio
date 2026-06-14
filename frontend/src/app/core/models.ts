export interface UserDto {
  id: string;
  name: string;
}

export type Intent = 'TRACKING' | 'CANCEL' | 'SUPPORT' | 'OTHER' | 'NONE';

export interface ChatRequest {
  conversationId: string;
  message: string;
}

export interface ChatResponse {
  conversationId: string;
  reply: string;
  intent: Intent;
  resolved: boolean;
}

export interface ChatMessage {
  from: 'user' | 'agent';
  text: string;
  intent?: Intent;
  at: Date;
}
