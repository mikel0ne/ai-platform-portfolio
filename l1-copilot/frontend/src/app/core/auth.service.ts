import { Injectable, signal } from '@angular/core';
import { UserDto } from './models';

const STORAGE_KEY = 'l1.copilot.user';
const TOKEN_KEY = 'l1.copilot.token';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly _user = signal<UserDto | null>(this.restore());
  readonly user = this._user.asReadonly();
  private readonly _token = signal<string | null>(sessionStorage.getItem(TOKEN_KEY));
  readonly token = this._token.asReadonly();

  login(user: UserDto, token: string): void {
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(user));
    sessionStorage.setItem(TOKEN_KEY, token);
    this._user.set(user);
    this._token.set(token);
  }

  logout(): void {
    sessionStorage.removeItem(STORAGE_KEY);
    sessionStorage.removeItem(TOKEN_KEY);
    this._user.set(null);
    this._token.set(null);
  }

  private restore(): UserDto | null {
    const raw = sessionStorage.getItem(STORAGE_KEY);
    if (!raw) return null;
    try {
      return JSON.parse(raw) as UserDto;
    } catch {
      sessionStorage.removeItem(STORAGE_KEY);
      return null;
    }
  }
}
