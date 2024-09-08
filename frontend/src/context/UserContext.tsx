import { createContext, useReducer, ReactNode, Dispatch, useMemo } from "react";

// Define the shape of the user state
interface UserState {
  isAuthenticated: boolean;
  user: { name: string } | null;
}

// Define the shape of the actions
type Action =
  | {
      type: "LOGIN";
      payload: { name: string };
    }
  | { type: "LOGOUT" };

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

// Define the reducer function
const userReducer = (state: UserState, action: Action): UserState => {
  switch (action.type) {
    case "LOGIN":
      return {
        isAuthenticated: true,
        user: { name: action.payload.name },
      };
    case "LOGOUT":
      return {
        isAuthenticated: false,
        user: null,
      };
    default:
      return state;
  }
};

// Create a provider component
const UserProvider = ({ children }: { children: ReactNode }) => {
  const [state, dispatch] = useReducer(userReducer, initialState);

  const contextValue = useMemo(() => ({ state, dispatch }), [state, dispatch]);

  return (
    <UserContext.Provider value={contextValue}>{children}</UserContext.Provider>
  );
};

export { UserContext, UserProvider };
