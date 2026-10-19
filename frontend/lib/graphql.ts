import { gql } from "@apollo/client";
import { useMutation, useQuery, useSubscription } from "@apollo/client/react";

export const DATASOURCES_QUERY = gql`
  query DataSources {
    dataSources {
      id
      name
      engine
      sandbox
      createdAt
    }
  }
`;

export const ADD_DATASOURCE_MUTATION = gql`
  mutation AddDataSource(
    $name: String!
    $engine: Engine!
    $jdbcUrl: String!
    $username: String
    $password: String
  ) {
    addDataSource(
      input: {
        name: $name
        engine: $engine
        jdbcUrl: $jdbcUrl
        username: $username
        password: $password
      }
    ) {
      dataSource {
        id
        name
        engine
        sandbox
      }
      warnings
    }
  }
`;

export const ASK_QUESTION_MUTATION = gql`
  mutation AskQuestion($input: AskQuestionInput!) {
    askQuestion(input: $input) {
      jobId
      status
    }
  }
`;

export const JOB_QUERY = gql`
  query Job($id: ID!) {
    job(id: $id) {
      id
      status
      progress
      result {
        columns { name typeName }
        rows
        truncated
      }
      errorMessage
      analysis
      clarifyingQuestions
    }
  }
`;

export const JOB_UPDATES_SUBSCRIPTION = gql`
  subscription JobUpdates($jobId: ID!) {
    jobUpdates(jobId: $jobId) {
      id
      status
      progress
      result {
        columns { name typeName }
        rows
        truncated
      }
      errorMessage
      analysis
      clarifyingQuestions
    }
  }
`;

export interface JobView {
  id: string;
  status: string;
  progress?: number;
  result: {
    columns: { name: string; typeName: string }[];
    rows: unknown[];
    truncated: boolean;
  } | null;
  errorMessage?: string;
  analysis?: string | null;
  clarifyingQuestions?: string[] | null;
}

export const DASHBOARD_QUERY = gql`
  query Dashboard {
    dashboard {
      id
      jobId
      question
      engine
      createdAt
      result {
        columns { name typeName }
        rows
        truncated
      }
    }
  }
`;

export const SAVE_TO_DASHBOARD_MUTATION = gql`
  mutation SaveToDashboard($jobId: ID!) {
    saveToDashboard(jobId: $jobId) {
      id
      question
      engine
      createdAt
    }
  }
`;

export const REMOVE_DASHBOARD_CARD_MUTATION = gql`
  mutation RemoveDashboardCard($id: ID!) {
    removeDashboardCard(id: $id)
  }
`;

export function useDataSources() {
  return useQuery(DATASOURCES_QUERY);
}

export function useAddDataSource() {
  return useMutation(ADD_DATASOURCE_MUTATION);
}

export function useAskQuestion() {
  return useMutation(ASK_QUESTION_MUTATION);
}

export function useJob(id: string | null) {
  return useQuery(JOB_QUERY, { variables: { id }, skip: !id });
}

/**
 * Live job stage updates over the graphql-transport-ws WebSocket. The
 * stream emits the current snapshot first, then every stage transition
 * until the job reaches a terminal status, then completes.
 */
export function useJobUpdates(jobId: string | null) {
  return useSubscription(JOB_UPDATES_SUBSCRIPTION, {
    variables: { jobId: jobId ?? '' },
    skip: !jobId,
  });
}

/** The signed-in user's pinned dashboard cards, newest first. */
export function useDashboard() {
  return useQuery(DASHBOARD_QUERY);
}

export function useSaveToDashboard() {
  return useMutation(SAVE_TO_DASHBOARD_MUTATION, {
    refetchQueries: [{ query: DASHBOARD_QUERY }],
  });
}

export function useRemoveDashboardCard() {
  return useMutation(REMOVE_DASHBOARD_CARD_MUTATION, {
    refetchQueries: [{ query: DASHBOARD_QUERY }],
  });
}

// ─── Presentation boards (widget dashboards, max 6 widgets) ──────────────────

export const BOARDS_QUERY = gql`
  query Boards {
    boards {
      id
      title
      createdAt
    }
  }
`;

export const BOARD_QUERY = gql`
  query Board($id: ID!) {
    board(id: $id) {
      id
      title
      createdAt
      widgets {
        id
        boardId
        title
        dataSourceId
        mode
        tableName
        sqlText
        question
        chartKind
        ordinal
        status
        errorMessage
        result {
          columns { name typeName }
          rows
          truncated
        }
        createdAt
        updatedAt
      }
    }
  }
`;

export const TABLES_QUERY = gql`
  query SchemaTables($dataSourceId: ID!) {
    schemaTables(dataSourceId: $dataSourceId)
  }
`;

export const CREATE_BOARD_MUTATION = gql`
  mutation CreateBoard($title: String!) {
    createBoard(title: $title) {
      id
      title
    }
  }
`;

export const SAVE_BOARD_WIDGET_MUTATION = gql`
  mutation SaveBoardWidget($boardId: ID!, $input: BoardWidgetInput!) {
    saveBoardWidget(boardId: $boardId, input: $input) {
      id
      boardId
      title
      dataSourceId
      mode
      tableName
      sqlText
      question
      chartKind
      ordinal
      status
      errorMessage
      result {
        columns { name typeName }
        rows
        truncated
      }
    }
  }
`;

export const REMOVE_BOARD_MUTATION = gql`
  mutation RemoveBoard($id: ID!) {
    removeBoard(id: $id)
  }
`;

export const REMOVE_BOARD_WIDGET_MUTATION = gql`
  mutation RemoveBoardWidget($boardId: ID!, $widgetId: ID!) {
    removeBoardWidget(boardId: $boardId, widgetId: $widgetId)
  }
`;

export interface BoardsReply {
  boards: { id: string; title: string; createdAt: string }[];
}

export interface BoardWidgetReply {
  id: string;
  boardId: string;
  title: string;
  dataSourceId: string;
  mode: "MANUAL" | "SQL" | "QUESTION";
  tableName?: string | null;
  sqlText?: string | null;
  question?: string | null;
  chartKind: "TABLE" | "BAR" | "LINE" | "PIE";
  ordinal: number;
  status: string;
  errorMessage?: string | null;
  result: {
    columns: { name: string; typeName: string }[];
    rows: unknown[];
    truncated: boolean;
  } | null;
}

export interface BoardReply {
  board: {
    id: string;
    title: string;
    createdAt: string;
    widgets: BoardWidgetReply[];
  };
}

export function useBoards() {
  return useQuery(BOARDS_QUERY);
}

export function useBoard(id: string | null) {
  return useQuery(BOARD_QUERY, { variables: { id }, skip: !id });
}

export function useSchemaTables(dataSourceId: string | null) {
  return useQuery(TABLES_QUERY, { variables: { dataSourceId }, skip: !dataSourceId });
}

export function useCreateBoard() {
  return useMutation(CREATE_BOARD_MUTATION, {
    refetchQueries: [{ query: BOARDS_QUERY }],
  });
}

export function useSaveBoardWidget() {
  return useMutation(SAVE_BOARD_WIDGET_MUTATION);
}

export function useRemoveBoard() {
  return useMutation(REMOVE_BOARD_MUTATION, {
    refetchQueries: [{ query: BOARDS_QUERY }],
  });
}

export function useRemoveBoardWidget() {
  return useMutation(REMOVE_BOARD_WIDGET_MUTATION);
}
