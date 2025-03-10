import React from "react";
import xhrMock from "xhr-mock";
import {
  fireEvent,
  renderWithProviders,
  screen,
  waitFor,
} from "__support__/ui";
import {
  SAMPLE_DATASET,
  ORDERS,
  metadata,
} from "__support__/sample_dataset_fixture";
import Question from "metabase-lib/lib/Question";
import { ViewTitleHeader } from "./ViewHeader";

const BASE_GUI_QUESTION = {
  display: "table",
  visualization_settings: {},
  dataset_query: {
    type: "query",
    database: SAMPLE_DATASET.id,
    query: {
      "source-table": ORDERS.id,
    },
  },
};

const FILTERED_GUI_QUESTION = {
  ...BASE_GUI_QUESTION,
  dataset_query: {
    ...BASE_GUI_QUESTION.dataset_query,
    query: {
      ...BASE_GUI_QUESTION.dataset_query.query,
      filter: [
        "and",
        ["<", ["field", ORDERS.TOTAL.id, null], 50],
        ["not-null", ["field", ORDERS.TAX.id, null]],
      ],
    },
  },
};

const BASE_NATIVE_QUESTION = {
  display: "table",
  visualization_settings: {},
  dataset_query: {
    type: "native",
    database: SAMPLE_DATASET.id,
    native: {
      query: "select * from orders",
    },
  },
};

const SAVED_QUESTION = {
  id: 1,
  name: "Q1",
  description: null,
  collection_id: null,
};

function getQuestion(card) {
  return new Question(card, metadata);
}

function getAdHocQuestion(overrides) {
  return getQuestion({ ...BASE_GUI_QUESTION, ...overrides });
}

function getNativeQuestion() {
  return getQuestion(BASE_NATIVE_QUESTION);
}

function getSavedGUIQuestion(overrides) {
  return getQuestion({ ...BASE_GUI_QUESTION, ...SAVED_QUESTION, ...overrides });
}

function getSavedNativeQuestion(overrides) {
  return getQuestion({
    ...BASE_NATIVE_QUESTION,
    ...SAVED_QUESTION,
    ...overrides,
  });
}

function setup({ question, isRunnable = true, ...props } = {}) {
  const callbacks = {
    runQuestionQuery: jest.fn(),
    setQueryBuilderMode: jest.fn(),
    onOpenQuestionDetails: jest.fn(),
    onCloseQuestionDetails: jest.fn(),
    onOpenModal: jest.fn(),
    onAddFilter: jest.fn(),
    onCloseFilter: jest.fn(),
    onEditSummary: jest.fn(),
    onCloseSummary: jest.fn(),
  };

  renderWithProviders(
    <ViewTitleHeader
      {...callbacks}
      {...props}
      isRunnable={isRunnable}
      question={question}
    />,
    {
      withRouter: true,
      withSampleDataset: true,
    },
  );

  return { question, ...callbacks };
}

function setupAdHoc(props = {}) {
  return setup({ question: getAdHocQuestion(), ...props });
}

function setupNative(props) {
  return setup({ question: getNativeQuestion(), ...props });
}

function setupSavedNative(props = {}) {
  const collection = {
    id: "root",
    name: "Our analytics",
  };

  xhrMock.get("/api/collection/root", {
    body: JSON.stringify(collection),
  });

  const utils = setup({ question: getSavedNativeQuestion(), ...props });

  return {
    ...utils,
    collection,
  };
}

describe("ViewHeader", () => {
  const TEST_CASE = {
    SAVED_GUI_QUESTION: {
      question: getSavedGUIQuestion(),
      questionType: "saved GUI question",
    },
    AD_HOC_QUESTION: {
      question: getAdHocQuestion(),
      questionType: "ad-hoc GUI question",
    },
    NATIVE_QUESTION: {
      question: getNativeQuestion(),
      questionType: "not saved native question",
    },
    SAVED_NATIVE_QUESTION: {
      question: getSavedNativeQuestion(),
      questionType: "saved native question",
    },
  };

  const ALL_TEST_CASES = Object.values(TEST_CASE);
  const GUI_TEST_CASES = [
    TEST_CASE.SAVED_GUI_QUESTION,
    TEST_CASE.AD_HOC_QUESTION,
  ];
  const NATIVE_TEST_CASES = [
    TEST_CASE.SAVED_NATIVE_QUESTION,
    TEST_CASE.NATIVE_QUESTION,
  ];
  const SAVED_QUESTIONS_TEST_CASES = [
    TEST_CASE.SAVED_GUI_QUESTION,
    TEST_CASE.SAVED_NATIVE_QUESTION,
  ];

  describe("Common", () => {
    ALL_TEST_CASES.forEach(testCase => {
      const { question, questionType } = testCase;

      describe(questionType, () => {
        it("offers to save", () => {
          const { onOpenModal } = setup({ question, isDirty: true });
          fireEvent.click(screen.getByText("Save"));
          expect(onOpenModal).toHaveBeenCalledWith("save");
        });

        it("does not offer to save if it's not dirty", () => {
          setup({ question, isDirty: false });
          expect(screen.queryByText("Save")).not.toBeInTheDocument();
        });

        it("offers to refresh query results", () => {
          const { runQuestionQuery } = setup({ question });
          fireEvent.click(screen.getByLabelText("refresh icon"));
          expect(runQuestionQuery).toHaveBeenCalledWith({ ignoreCache: true });
        });

        it("does not offer to refresh query results if question is not runnable", () => {
          setup({ question, isRunnable: false });
          expect(
            screen.queryByLabelText("refresh icon"),
          ).not.toBeInTheDocument();
        });
      });
    });
  });

  describe("GUI", () => {
    GUI_TEST_CASES.forEach(testCase => {
      const { question, questionType } = testCase;

      describe(questionType, () => {
        it("displays database and table names", () => {
          setup({ question });
          const databaseName = question.database().displayName();
          const tableName = question.table().displayName();

          expect(screen.queryByText(databaseName)).toBeInTheDocument();
          expect(screen.queryByText(tableName)).toBeInTheDocument();
        });

        it("offers to filter query results", () => {
          const { onAddFilter } = setup({
            question,
            queryBuilderMode: "view",
          });
          fireEvent.click(screen.getByText("Filter"));
          expect(onAddFilter).toHaveBeenCalled();
        });

        it("offers to summarize query results", () => {
          const { onEditSummary } = setup({
            question,
            queryBuilderMode: "view",
          });
          fireEvent.click(screen.getByText("Summarize"));
          expect(onEditSummary).toHaveBeenCalled();
        });

        it("allows to open notebook editor", () => {
          const { setQueryBuilderMode } = setup({
            question,
            queryBuilderMode: "view",
          });
          fireEvent.click(screen.getByLabelText("notebook icon"));
          expect(setQueryBuilderMode).toHaveBeenCalledWith("notebook");
        });

        it("allows to close notebook editor", () => {
          const { setQueryBuilderMode } = setup({
            question,
            queryBuilderMode: "notebook",
          });
          fireEvent.click(screen.getByLabelText("notebook icon"));
          expect(setQueryBuilderMode).toHaveBeenCalledWith("view");
        });

        it("does not offer to filter query results in notebook mode", () => {
          setup({ question, queryBuilderMode: "notebook" });
          expect(screen.queryByText("Filter")).not.toBeInTheDocument();
        });

        it("does not offer to filter query in detail view", () => {
          setup({ question, isObjectDetail: true });
          expect(screen.queryByText("Filter")).not.toBeInTheDocument();
        });

        it("does not offer to summarize query results in notebook mode", () => {
          setup({ question, queryBuilderMode: "notebook" });
          expect(screen.queryByText("Summarize")).not.toBeInTheDocument();
        });

        it("does not offer to summarize query in detail view", () => {
          setup({ question, isObjectDetail: true });
          expect(screen.queryByText("Summarize")).not.toBeInTheDocument();
        });
      });
    });
  });

  describe("Native", () => {
    NATIVE_TEST_CASES.forEach(testCase => {
      const { question, questionType } = testCase;

      describe(questionType, () => {
        it("does not offer to filter query results", () => {
          setup({ question });
          expect(screen.queryByText("Filter")).not.toBeInTheDocument();
        });

        it("does not offer to summarize query results", () => {
          setup({ question });
          expect(screen.queryByText("Summarize")).not.toBeInTheDocument();
        });

        it("does not offer to refresh query results if native editor is open", () => {
          setup({ question, isNativeEditorOpen: true });
          expect(
            screen.queryByLabelText("refresh icon"),
          ).not.toBeInTheDocument();
        });
      });
    });
  });

  describe("Saved", () => {
    SAVED_QUESTIONS_TEST_CASES.forEach(testCase => {
      const { question, questionType } = testCase;

      describe(questionType, () => {
        beforeEach(() => {
          xhrMock.setup();
          xhrMock.get("/api/collection/root", {
            body: JSON.stringify({
              id: "root",
              name: "Our analytics",
            }),
          });
        });

        afterEach(() => {
          xhrMock.teardown();
        });

        it("displays collection where a question is saved to", async () => {
          setup({ question });
          await waitFor(() => screen.queryByText("Our analytics"));
          expect(screen.queryByText("Our analytics")).toBeInTheDocument();
        });

        it("opens details sidebar on question name click", () => {
          const { onOpenQuestionDetails } = setup({ question });
          fireEvent.click(screen.getByText(question.displayName()));
          expect(onOpenQuestionDetails).toHaveBeenCalled();
        });
      });
    });
  });
});

describe("ViewHeader | Ad-hoc GUI question", () => {
  it("does not open details sidebar on table name click", () => {
    const { question, onOpenQuestionDetails } = setupAdHoc();
    const tableName = question.table().displayName();

    fireEvent.click(screen.getByText(tableName));

    expect(onOpenQuestionDetails).not.toHaveBeenCalled();
  });

  it("displays original question name if a question is started from one", () => {
    const originalQuestion = getSavedGUIQuestion();
    setupAdHoc({ originalQuestion });

    expect(screen.queryByText("Started from")).toBeInTheDocument();
    expect(
      screen.queryByText(originalQuestion.displayName()),
    ).toBeInTheDocument();
  });

  describe("filters", () => {
    const question = getAdHocQuestion(FILTERED_GUI_QUESTION);

    it("shows all filters by default", () => {
      setup({ question, queryBuilderMode: "view" });
      expect(screen.queryByText("Total is less than 50")).toBeInTheDocument();
      expect(screen.queryByText("Tax is not empty")).toBeInTheDocument();
    });

    it("can collapse and expand filters", () => {
      setup({ question, queryBuilderMode: "view" });

      fireEvent.click(screen.getByTestId("filters-visibility-control"));

      expect(
        screen.queryByText("Total is less than 50"),
      ).not.toBeInTheDocument();
      expect(screen.queryByText("Tax is not empty")).not.toBeInTheDocument();

      fireEvent.click(screen.getByTestId("filters-visibility-control"));

      expect(screen.queryByText("Total is less than 50")).toBeInTheDocument();
      expect(screen.queryByText("Tax is not empty")).toBeInTheDocument();
    });

    it("does not show filters in notebook mode", () => {
      setup({ question, queryBuilderMode: "notebook" });

      expect(
        screen.queryByTestId("filters-visibility-control"),
      ).not.toBeInTheDocument();
      expect(
        screen.queryByText("Total is less than 50"),
      ).not.toBeInTheDocument();
      expect(screen.queryByText("Tax is not empty")).not.toBeInTheDocument();
    });
  });
});

describe("View Header | Saved GUI question", () => {
  describe("filters", () => {
    const question = getSavedGUIQuestion(FILTERED_GUI_QUESTION);

    it("shows filters collapsed by default", () => {
      setup({ question, queryBuilderMode: "view" });

      expect(
        screen.queryByTestId("filters-visibility-control"),
      ).toBeInTheDocument();
      expect(
        screen.queryByText("Total is less than 50"),
      ).not.toBeInTheDocument();
      expect(screen.queryByText("Tax is not empty")).not.toBeInTheDocument();
    });

    it("can collapse and expand filters", () => {
      setup({ question, queryBuilderMode: "view" });

      fireEvent.click(screen.getByTestId("filters-visibility-control"));

      expect(screen.queryByText("Total is less than 50")).toBeInTheDocument();
      expect(screen.queryByText("Tax is not empty")).toBeInTheDocument();

      fireEvent.click(screen.getByTestId("filters-visibility-control"));

      expect(
        screen.queryByText("Total is less than 50"),
      ).not.toBeInTheDocument();
      expect(screen.queryByText("Tax is not empty")).not.toBeInTheDocument();
    });

    it("does not show filters in notebook mode", () => {
      setup({ question, queryBuilderMode: "notebook" });

      expect(
        screen.queryByTestId("filters-visibility-control"),
      ).not.toBeInTheDocument();
      expect(
        screen.queryByText("Total is less than 50"),
      ).not.toBeInTheDocument();
      expect(screen.queryByText("Tax is not empty")).not.toBeInTheDocument();
    });
  });
});

describe("View Header | Not saved native question", () => {
  it("does not display question database", () => {
    const { question } = setupNative();
    const databaseName = question.database().displayName();
    expect(screen.queryByText(databaseName)).not.toBeInTheDocument();
  });

  it("does not offer to explore query results", () => {
    setupNative();
    expect(screen.queryByText("Explore results")).not.toBeInTheDocument();
  });

  it("displays original question name if a question is started from one", () => {
    const originalQuestion = getSavedNativeQuestion();
    setupNative({ originalQuestion });

    expect(screen.queryByText("Started from")).toBeInTheDocument();
    expect(
      screen.queryByText(originalQuestion.displayName()),
    ).toBeInTheDocument();
  });
});

describe("View Header | Saved native question", () => {
  beforeEach(() => {
    xhrMock.setup();
  });

  afterEach(() => {
    xhrMock.teardown();
  });

  it("displays database a question is using", () => {
    const { question } = setupSavedNative();
    const databaseName = question.database().displayName();
    expect(screen.queryByText(databaseName)).toBeInTheDocument();
  });

  it("offers to explore query results", () => {
    setupSavedNative();
    expect(screen.queryByText("Explore results")).toBeInTheDocument();
  });
});
