import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ChatRequest, ChatResponse, LoginResponse, ReturnLabel, TicketDetail, UserDto } from './models';

@Injectable({ providedIn: 'root' })
export class ChatApiService {
  private readonly http = inject(HttpClient);

  users(): Observable<UserDto[]> {
    return this.http.get<UserDto[]>('/api/copilot/users');
  }

  login(userId: string): Observable<LoginResponse> {
    return this.http.post<LoginResponse>('/api/copilot/login', { userId });
  }

  send(req: ChatRequest): Observable<ChatResponse> {
    return this.http.post<ChatResponse>('/api/copilot/chat', req);
  }

  ticket(id: number): Observable<TicketDetail> {
    return this.http.get<TicketDetail>(`/api/copilot/tickets/${id}`);
  }

  returnLabel(id: number): Observable<ReturnLabel> {
    return this.http.get<ReturnLabel>(`/api/copilot/returns/${id}`);
  }
}
