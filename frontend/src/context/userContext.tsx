import { createContext, useReducer, ReactNode, Dispatch, useMemo } from "react";

import userReducer, { UserState, Action } from "./userReducer";

// Define the initial state
const initialState: UserState = {
  isAuthenticated: false,
  user: null,
};

// Create the context
const UserContext = createContext<{
  state: UserState;
  dispatch: Dispatch<Action>;
}>({
  state: initialState,
  dispatch: () => null,
});

// Create a provider component
const UserProvider = ({ children }: { children: ReactNode }) => {
  const [state, dispatch] = useReducer(userReducer, initialState);

  const contextValue = useMemo(() => ({ state, dispatch }), [state, dispatch]);

  return (
    <UserContext.Provider value={contextValue}>{children}</UserContext.Provider>
  );
};

export { UserContext, UserProvider };
