import { useState, useEffect, useContext } from "react";
import { useNavigate } from "react-router-dom";
import { UserContext } from "../context/UserContext";
import { Button } from "./ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from "./ui/card";
import { Input } from "./ui/input";
import { Label } from "./ui/label";
import { isValidEmail } from "../utils/validate";
import useFetch from "../hooks/useFetch";

export const description =
  "A simple login form with email and password. The submit button says 'Sign in'.";

export function LoginForm() {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const { setEmailAfterValidation } = useContext(UserContext);

  const isValidEmailCheck = isValidEmail(email);
  const isFormValid = isValidEmailCheck && password !== "";

  const navigate = useNavigate();

  const { performFetch, cancelFetch } = useFetch(
    `/email/${email}`,
    (response) => {
      setEmailAfterValidation(email);
      if (response.result) {
        navigate("/login");
      } else {
        navigate("/signup");
      }
    }
  );

  useEffect(() => {
    let timeoutId: NodeJS.Timeout;

    if (isValidEmailCheck) {
      timeoutId = setTimeout(() => {
        performFetch();
      }, 3000);
    }

    return () => {
      clearTimeout(timeoutId);
      cancelFetch();
    };
  }, [email, isValidEmailCheck, performFetch, cancelFetch]);

  return (
    <div className="flex items-center justify-center h-screen w-screen">
      <Card className="w-full max-w-sm">
        <CardHeader>
          <CardTitle className="text-2xl">Login</CardTitle>
          <CardDescription>
            Enter your email below to login to your account.
          </CardDescription>
        </CardHeader>
        <CardContent className="grid gap-4">
          <div className="grid gap-2">
            <Label htmlFor="email">Email</Label>
            <Input
              id="email"
              type="email"
              placeholder="m@example.com"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              required
            />
          </div>
          {isValidEmailCheck && (
            <div className="grid gap-2">
              <Label htmlFor="password">Password</Label>
              <Input
                id="password"
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                required
              />
            </div>
          )}
        </CardContent>
        <CardFooter>
          <Button
            className="w-full"
            label="Sign in"
            disabled={!isFormValid}
          ></Button>
        </CardFooter>
      </Card>
    </div>
  );
}
