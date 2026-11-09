import { initializeApp, getApps, getApp } from 'firebase/app';
import {
  getAuth,
  GoogleAuthProvider,
  signInWithPopup,
  signOut as firebaseSignOut,
  User,
} from 'firebase/auth';
import firebaseConfig from '../../firebase-applet-config.json';
import {
  TaskItem,
  TaskScheduleCategory,
  MAX_PAST_TASK_AGE_DAYS,
} from '../utils/calendarSyncEngine';

const app = getApps().length === 0 ? initializeApp(firebaseConfig) : getApp();
export const auth = getAuth(app);

// In-memory OAuth access token cache (never stored in localStorage/sessionStorage per security guidelines)
let inMemoryAccessToken: string | null = null;
let inMemoryAccountEmail: string | null = null;

export const GOOGLE_TASKS_SCOPES = [
  'https://www.googleapis.com/auth/tasks',
  'https://www.googleapis.com/auth/tasks.readonly',
];

const TASKS_API_BASE = 'https://tasks.googleapis.com/tasks/v1';

export interface GoogleTaskListSummary {
  id: string;
  title: string;
}

export interface GoogleTasksSyncResult {
  tasks: TaskItem[];
  accountEmail: string;
  syncedAtHHMM: string;
  totalListsScanned: number;
}

export function getInMemoryGoogleTasksToken(): string | null {
  return inMemoryAccessToken;
}

export function getInMemoryGoogleTasksEmail(): string | null {
  return inMemoryAccountEmail || auth.currentUser?.email || null;
}

export async function signInWithGoogleTasks(): Promise<{
  accessToken: string;
  user: User;
}> {
  const provider = new GoogleAuthProvider();
  for (const scope of GOOGLE_TASKS_SCOPES) {
    provider.addScope(scope);
  }
  provider.setCustomParameters({
    prompt: 'consent',
  });

  const result = await signInWithPopup(auth, provider);
  const credential = GoogleAuthProvider.credentialFromResult(result);
  const token = credential?.accessToken;
  if (!token) {
    throw new Error(
      'Não foi possível obter o token OAuth 2.0 para a API do Google Tasks.'
    );
  }

  inMemoryAccessToken = token;
  inMemoryAccountEmail = result.user.email;
  return { accessToken: token, user: result.user };
}

export async function signOutGoogleTasks(): Promise<void> {
  inMemoryAccessToken = null;
  inMemoryAccountEmail = null;
  await firebaseSignOut(auth);
}

function classifyRfc3339DueDate(
  dueRfc3339: string | undefined,
  updatedRfc3339: string | undefined,
  referenceNowMs: number
): {
  category: TaskScheduleCategory;
  dueHHMM: string;
  dueEpochMs?: number;
  ageDays?: number;
} | null {
  const nowDate = new Date(referenceNowMs);
  const startOfTodayMs = new Date(
    nowDate.getFullYear(),
    nowDate.getMonth(),
    nowDate.getDate()
  ).getTime();
  const maxPastCutoffMs =
    startOfTodayMs - MAX_PAST_TASK_AGE_DAYS * 24 * 60 * 60 * 1000;

  if (!dueRfc3339 || !dueRfc3339.trim()) {
    if (updatedRfc3339) {
      const updatedMs = Date.parse(updatedRfc3339);
      if (!Number.isNaN(updatedMs) && updatedMs < maxPastCutoffMs) {
        return null;
      }
    }
    return {
      category: 'UNDATED',
      dueHHMM: '',
    };
  }

  const datePrefix = dueRfc3339.slice(0, 10);
  const [yStr, mStr, dStr] = datePrefix.split('-');
  const year = Number(yStr);
  const month = Number(mStr) - 1;
  const day = Number(dStr);
  if (Number.isNaN(year) || Number.isNaN(month) || Number.isNaN(day)) {
    return {
      category: 'UNDATED',
      dueHHMM: '',
    };
  }

  const dueLocalDayStartMs = new Date(year, month, day).getTime();
  const diffDays = Math.round(
    (dueLocalDayStartMs - startOfTodayMs) / (24 * 60 * 60 * 1000)
  );

  // Tarefas com mais de 30 dias no passado são descartadas
  if (diffDays < -MAX_PAST_TASK_AGE_DAYS) {
    return null;
  }
  // Tarefas futuras além de amanhã não entram no quadro de tarefas
  if (diffDays > 1) {
    return null;
  }

  // Extrai horário específico caso não seja meia-noite UTC
  let extractedHHMM = '';
  const parsedFullMs = Date.parse(dueRfc3339);
  if (
    !Number.isNaN(parsedFullMs) &&
    !dueRfc3339.includes('T00:00:00.000Z') &&
    !dueRfc3339.endsWith('T00:00:00Z')
  ) {
    const dt = new Date(parsedFullMs);
    const hh = String(dt.getHours()).padStart(2, '0');
    const mm = String(dt.getMinutes()).padStart(2, '0');
    if (hh !== '00' || mm !== '00') {
      extractedHHMM = `${hh}:${mm}`;
    }
  }

  if (diffDays < 0) {
    return {
      category: 'OVERDUE',
      dueHHMM: '',
      dueEpochMs: dueLocalDayStartMs,
      ageDays: Math.abs(diffDays),
    };
  }

  if (diffDays === 0) {
    return {
      category: 'TODAY',
      dueHHMM: extractedHHMM || '09:00',
      dueEpochMs: dueLocalDayStartMs,
      ageDays: 0,
    };
  }

  return {
    category: 'TOMORROW',
    dueHHMM: extractedHHMM || '09:00',
    dueEpochMs: dueLocalDayStartMs,
    ageDays: 0,
  };
}

export async function fetchGoogleTasksFromApi(
  explicitToken?: string,
  referenceNowMs: number = Date.now()
): Promise<GoogleTasksSyncResult> {
  const token = explicitToken || inMemoryAccessToken;
  if (!token) {
    throw new Error(
      'Conecte sua conta Google para sincronizar com a Google Tasks API.'
    );
  }

  const listsResp = await fetch(`${TASKS_API_BASE}/users/@me/lists?maxResults=20`, {
    headers: {
      Authorization: `Bearer ${token}`,
    },
  });

  if (listsResp.status === 401) {
    inMemoryAccessToken = null;
    throw new Error(
      'Sessão OAuth expirada. Clique em "Sincronizar com Google Tasks" para renovar o acesso.'
    );
  }

  if (!listsResp.ok) {
    throw new Error(
      `Erro HTTP ${listsResp.status} ao listar quadros do Google Tasks.`
    );
  }

  const listsData = (await listsResp.json()) as {
    items?: Array<{ id: string; title: string }>;
  };
  const taskLists = listsData.items ?? [{ id: '@default', title: 'Minhas tarefas' }];

  const collected: TaskItem[] = [];

  for (const list of taskLists) {
    const tasksUrl = `${TASKS_API_BASE}/lists/${encodeURIComponent(
      list.id
    )}/tasks?showCompleted=false&showHidden=false&maxResults=100`;

    const tasksResp = await fetch(tasksUrl, {
      headers: {
        Authorization: `Bearer ${token}`,
      },
    });

    if (!tasksResp.ok) continue;

    const tasksData = (await tasksResp.json()) as {
      items?: Array<{
        id: string;
        title?: string;
        status?: string;
        due?: string;
        updated?: string;
        deleted?: boolean;
        hidden?: boolean;
      }>;
    };

    for (const rawTask of tasksData.items ?? []) {
      if (rawTask.deleted || rawTask.hidden) continue;
      if (rawTask.status === 'completed') continue;
      const cleanTitle = (rawTask.title ?? '').trim();
      if (!cleanTitle) continue;

      const classified = classifyRfc3339DueDate(
        rawTask.due,
        rawTask.updated,
        referenceNowMs
      );
      if (!classified) continue;

      collected.push({
        id: `gtask_${rawTask.id}`,
        googleTaskId: rawTask.id,
        googleTaskListId: list.id,
        title: cleanTitle,
        dueHHMM: classified.dueHHMM,
        sourceLabel: 'Tasks',
        completed: false,
        scheduleCategory: classified.category,
        isOverdue: classified.category === 'OVERDUE',
        isTodayOrTimeless: true,
        dueEpochMs: classified.dueEpochMs,
        ageDays: classified.ageDays,
      });
    }
  }

  const now = new Date();
  const syncedAtHHMM = `${String(now.getHours()).padStart(2, '0')}:${String(
    now.getMinutes()
  ).padStart(2, '0')}`;

  return {
    tasks: collected,
    accountEmail: getInMemoryGoogleTasksEmail() || 'Conta Google Conectada',
    syncedAtHHMM,
    totalListsScanned: taskLists.length,
  };
}

export async function createTaskInGoogleTasksApi(params: {
  title: string;
  scheduleCategory: TaskScheduleCategory;
  dueHHMM?: string;
  referenceNowMs?: number;
}): Promise<TaskItem> {
  const token = inMemoryAccessToken;
  if (!token) {
    throw new Error('Conecte sua conta Google Tasks primeiro.');
  }

  const refNow = new Date(params.referenceNowMs ?? Date.now());
  let dueIsoRfc3339: string | undefined;

  if (params.scheduleCategory === 'TODAY') {
    const d = new Date(
      Date.UTC(refNow.getFullYear(), refNow.getMonth(), refNow.getDate(), 9, 0, 0)
    );
    dueIsoRfc3339 = d.toISOString();
  } else if (params.scheduleCategory === 'TOMORROW') {
    const d = new Date(
      Date.UTC(
        refNow.getFullYear(),
        refNow.getMonth(),
        refNow.getDate() + 1,
        9,
        0,
        0
      )
    );
    dueIsoRfc3339 = d.toISOString();
  }

  const bodyPayload: Record<string, string> = {
    title: params.title.trim(),
  };
  if (dueIsoRfc3339) {
    bodyPayload.due = dueIsoRfc3339;
  }

  const resp = await fetch(`${TASKS_API_BASE}/lists/@default/tasks`, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(bodyPayload),
  });

  if (!resp.ok) {
    throw new Error(`Falha ao criar tarefa na Google Tasks API (${resp.status}).`);
  }

  const created = (await resp.json()) as { id: string; title?: string };
  return {
    id: `gtask_${created.id}`,
    googleTaskId: created.id,
    googleTaskListId: '@default',
    title: created.title || params.title.trim(),
    dueHHMM:
      params.scheduleCategory === 'TODAY' ||
      params.scheduleCategory === 'TOMORROW'
        ? params.dueHHMM || '09:00'
        : '',
    sourceLabel: 'Tasks',
    completed: false,
    scheduleCategory: params.scheduleCategory,
    isOverdue: params.scheduleCategory === 'OVERDUE',
    isTodayOrTimeless: true,
  };
}

export async function completeTaskInGoogleTasksApi(
  task: TaskItem,
  completed: boolean
): Promise<void> {
  const token = inMemoryAccessToken;
  if (!token || !task.googleTaskId) return;

  const listId = task.googleTaskListId || '@default';
  const resp = await fetch(
    `${TASKS_API_BASE}/lists/${encodeURIComponent(
      listId
    )}/tasks/${encodeURIComponent(task.googleTaskId)}`,
    {
      method: 'PATCH',
      headers: {
        Authorization: `Bearer ${token}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        status: completed ? 'completed' : 'needsAction',
        completed: completed ? new Date().toISOString() : null,
      }),
    }
  );

  if (!resp.ok) {
    throw new Error(
      `Falha ao atualizar status da tarefa no Google Tasks (${resp.status}).`
    );
  }
}

export async function deleteTaskInGoogleTasksApi(task: TaskItem): Promise<void> {
  const token = inMemoryAccessToken;
  if (!token || !task.googleTaskId) return;

  const listId = task.googleTaskListId || '@default';
  const resp = await fetch(
    `${TASKS_API_BASE}/lists/${encodeURIComponent(
      listId
    )}/tasks/${encodeURIComponent(task.googleTaskId)}`,
    {
      method: 'DELETE',
      headers: {
        Authorization: `Bearer ${token}`,
      },
    }
  );

  if (!resp.ok && resp.status !== 204) {
    throw new Error(
      `Falha ao excluir tarefa no Google Tasks (${resp.status}).`
    );
  }
}
