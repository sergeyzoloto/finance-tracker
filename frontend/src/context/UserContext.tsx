import {
  createContext,
  useReducer,
  ReactNode,
  Dispatch,
  useMemo,
  useEffect,
  useCallback,
} from "react";
import { useNavigate } from "react-router-dom";
import userReducer, { UserState, Action } from "./UserReducer";

// Define the initial state
const initialState: UserState = {
  isAuthenticated: false,
  user: null,
};

// Create the context
const UserContext = createContext<{
  state: UserState;
  dispatch: Dispatch<Action>;
  checkAuth: () => void;
  setEmailAfterValidation: (email: string) => void;
}>({
  state: initialState,
  dispatch: () => null,
  checkAuth: () => null,
  setEmailAfterValidation: () => null,
});

// Create a provider component
const UserProvider = ({ children }: { children: ReactNode }) => {
  const [state, dispatch] = useReducer(userReducer, initialState);
  const navigate = useNavigate();

  const checkAuth = useCallback(() => {
    const token = localStorage.getItem("token");
    if (token) {
      dispatch({ type: "LOGIN", payload: { name: "User" } });
    } else {
      navigate("/login");
    }
  }, [navigate, dispatch]);

  const setEmailAfterValidation = useCallback(
    (email: string) => {
      dispatch({ type: "LOGIN", payload: { name: email } });
    },
    [dispatch]
  );

  useEffect(() => {
    checkAuth();
  }, []);

  const contextValue = useMemo(
    () => ({
      state,
      dispatch,
      checkAuth,
      setEmailAfterValidation,
    }),
    [state, dispatch, checkAuth, setEmailAfterValidation]
  );

  return (
    <UserContext.Provider value={contextValue}>{children}</UserContext.Provider>
  );
};

export { UserContext, UserProvider };
