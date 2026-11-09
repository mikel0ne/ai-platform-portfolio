import { CommonModule } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { AuthService } from '../core/auth.service';
import { ChatApiService } from '../core/chat-api.service';
import { UserDto } from '../core/models';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './login.component.html',
  styleUrl: './login.component.css',
})
export class LoginComponent {
  private readonly api = inject(ChatApiService);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  readonly users = signal<UserDto[]>([]);
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  readonly brokenAvatars = new Set<string>();

  constructor() {
    this.api.users().subscribe({
      next: u => {
        this.users.set(u);
        this.loading.set(false);
      },
      error: () => {
        this.error.set('No se pudieron cargar los usuarios. ¿Está el backend arrancado?');
        this.loading.set(false);
      },
    });
  }

  enter(user: UserDto): void {
    this.auth.login(user);
    this.router.navigateByUrl('/chat');
  }
}
