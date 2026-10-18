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
    }
  }
`;

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
