import { Injectable, signal } from '@angular/core';
import { UserDto } from './models';

const STORAGE_KEY = 'l1.copilot.user';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly _user = signal<UserDto | null>(this.restore());
  readonly user = this._user.asReadonly();

  login(user: UserDto): void {
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(user));
    this._user.set(user);
  }

  logout(): void {
    sessionStorage.removeItem(STORAGE_KEY);
    this._user.set(null);
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
