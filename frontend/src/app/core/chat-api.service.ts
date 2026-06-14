import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ChatRequest, ChatResponse, UserDto } from './models';

@Injectable({ providedIn: 'root' })
export class ChatApiService {
  private readonly http = inject(HttpClient);

  users(): Observable<UserDto[]> {
    return this.http.get<UserDto[]>('/api/copilot/users');
  }

  send(req: ChatRequest): Observable<ChatResponse> {
    return this.http.post<ChatResponse>('/api/copilot/chat', req);
  }
}
