/* eslint-disable react-hooks/exhaustive-deps */
import {
  createContext,
  useReducer,
  ReactNode,
  Dispatch,
  useMemo,
  useEffect,
} from "react";
import { useNavigate } from "react-router-dom";
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
  checkAuth: () => void;
}>({
  state: initialState,
  dispatch: () => null,
  checkAuth: () => null,
});

// Create a provider component
const UserProvider = ({ children }: { children: ReactNode }) => {
  const [state, dispatch] = useReducer(userReducer, initialState);
  const navigate = useNavigate();

  const checkAuth = () => {
    const token = localStorage.getItem("token");
    if (token) {
      dispatch({ type: "LOGIN", payload: { name: "User" } });
    } else {
      navigate("/login");
    }
  };

  useEffect(() => {
    checkAuth();
  }, []);

  const contextValue = useMemo(
    () => ({ state, dispatch, checkAuth }),
    [state, dispatch]
  );

  return (
    <UserContext.Provider value={contextValue}>{children}</UserContext.Provider>
  );
};

export { UserContext, UserProvider };
